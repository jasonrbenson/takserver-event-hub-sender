package tak.server.plugins;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import atakmap.commoncommo.protobuf.v1.MessageOuterClass.Message;
import tak.server.cot.CotEventContainer;
import tak.server.proto.StreamingProtoBufHelper;

@TakServerPlugin(
        name = "TAK Server Azure Event Hub Sender",
        description = "This plugin sends COT messages from TAK Server to one or more Azure Event Hub instances")
@TakServerPluginVersion(major = 1, minor = 0, patch = 0, tag = "multi-instance")
public class AzureEventHubSender extends MessageSenderReceiverBase {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final int HEALTH_LOG_INTERVAL_MINUTES = 5;

    private final List<EventHubSenderInstance> instances = Collections.synchronizedList(new ArrayList<>());
    private ScheduledExecutorService healthLogExecutor;

    @Override
    public void start() {
        try {
            List<EventHubSenderInstanceConfig> configs = loadConfigurations();

            logger.info("Starting {} Event Hub sender instance(s)", configs.size());

            for (EventHubSenderInstanceConfig instanceConfig : configs) {
                try {
                    EventHubSenderInstance instance = new EventHubSenderInstance(instanceConfig);
                    instance.start();
                    instances.add(instance);
                    logger.info("Instance [{}] started successfully", instanceConfig.getDisplayName());
                } catch (Exception e) {
                    logger.error("Failed to start instance [{}]: {} — continuing with other instances",
                            instanceConfig.getDisplayName(), e.getMessage(), e);
                }
            }

            if (instances.isEmpty()) {
                logger.error("No Event Hub sender instances started successfully!");
                return;
            }

            logger.info("{} of {} Event Hub sender instance(s) started successfully",
                    instances.size(), configs.size());

            // Schedule periodic health summary logging
            healthLogExecutor = Executors.newSingleThreadScheduledExecutor();
            healthLogExecutor.scheduleWithFixedDelay(this::logHealthSummary,
                    HEALTH_LOG_INTERVAL_MINUTES, HEALTH_LOG_INTERVAL_MINUTES, TimeUnit.MINUTES);

        } catch (Exception e) {
            logger.error("Fatal error starting Azure Event Hub Sender plugin", e);
        }
    }

    @Override
    public void onMessage(Message message) {
        if (instances.isEmpty()) {
            return;
        }

        try {
            CotEventContainer cotEvent = new StreamingProtoBufHelper().proto2cot(message.getPayload());
            String cotXml = cotEvent.asXml();

            if (cotXml == null || cotXml.isEmpty()) {
                logger.debug("Received empty CoT XML — skipping");
                return;
            }

            // Fan-out: send to all instances
            for (EventHubSenderInstance instance : instances) {
                try {
                    instance.sendMessage(cotXml);
                } catch (Exception e) {
                    logger.warn("Error sending to instance [{}]: {}",
                            instance.getDisplayName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Error converting message to CoT XML: {}", e.getMessage(), e);
        }
    }

    @Override
    public void stop() {
        logger.info("Stopping Azure Event Hub Sender plugin ({} instance(s))", instances.size());

        if (healthLogExecutor != null) {
            healthLogExecutor.shutdown();
            try {
                if (!healthLogExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    healthLogExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                healthLogExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            healthLogExecutor = null;
        }

        for (EventHubSenderInstance instance : instances) {
            try {
                instance.stop();
            } catch (Exception e) {
                logger.error("Error stopping instance [{}]: {}",
                        instance.getDisplayName(), e.getMessage(), e);
            }
        }
        instances.clear();

        logger.info("Azure Event Hub Sender plugin stopped");
    }

    /**
     * Load instance configurations.
     * Tries direct YAML file parsing first (supports multi-instance format).
     * Falls back to TAK Server config API (legacy single-instance only).
     */
    private List<EventHubSenderInstanceConfig> loadConfigurations() {
        // Try direct YAML parsing first — required for multi-instance support
        File yamlFile = findYamlConfigFile();
        if (yamlFile != null) {
            try {
                List<EventHubSenderInstanceConfig> configs = EventHubSenderConfigLoader.load(yamlFile);
                logger.info("Loaded configuration from YAML file: {}", yamlFile.getAbsolutePath());
                return configs;
            } catch (Exception e) {
                logger.warn("Failed to parse YAML file directly: {} — falling back to TAK config API",
                        e.getMessage());
            }
        }

        // Fallback: use TAK Server's config object (legacy single-instance format)
        logger.info("Using TAK Server config API for configuration (single-instance mode)");
        return EventHubSenderConfigLoader.loadFromTakConfig(config);
    }

    /**
     * Attempt to locate the YAML config file on disk.
     * TAK Server stores plugin configs in conf/plugins/ named after the fully-qualified class.
     */
    private File findYamlConfigFile() {
        String[] searchPaths = {
                "conf/plugins/tak.server.plugins.AzureEventHubSender.yaml",
                "/opt/tak/conf/plugins/tak.server.plugins.AzureEventHubSender.yaml",
        };

        for (String path : searchPaths) {
            File file = new File(path);
            if (file.exists() && file.canRead()) {
                return file;
            }
        }

        logger.debug("YAML config file not found in expected locations, will use TAK config API");
        return null;
    }

    private void logHealthSummary() {
        if (instances.isEmpty()) return;

        StringBuilder sb = new StringBuilder("=== Event Hub Sender Health Summary ===\n");
        long totalSent = 0, totalFailed = 0;
        int healthyCount = 0;

        for (EventHubSenderInstance instance : instances) {
            sb.append("  ").append(instance.getHealthSummary()).append("\n");
            totalSent += instance.getMessagesSent();
            totalFailed += instance.getMessagesFailed();
            if (instance.isHealthy()) healthyCount++;
        }

        sb.append(String.format("  TOTAL: %d/%d healthy, sent=%d, failed=%d",
                healthyCount, instances.size(), totalSent, totalFailed));

        logger.info(sb.toString());
    }
}
