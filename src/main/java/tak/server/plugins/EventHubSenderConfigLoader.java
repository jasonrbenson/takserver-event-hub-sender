package tak.server.plugins;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Loads Event Hub sender instance configurations from YAML.
 * Supports both multi-instance format (instances list) and legacy single-instance format.
 */
public class EventHubSenderConfigLoader {

    private static final Logger logger = LoggerFactory.getLogger(EventHubSenderConfigLoader.class);

    /**
     * Load configurations from a YAML file.
     * Auto-detects multi-instance vs. legacy single-instance format.
     *
     * @param yamlFile the YAML configuration file
     * @return list of instance configurations (never empty if file is valid)
     * @throws IOException if the file cannot be read or parsed
     */
    @SuppressWarnings("unchecked")
    public static List<EventHubSenderInstanceConfig> load(File yamlFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

        Map<String, Object> root = mapper.readValue(yamlFile, Map.class);
        Map<String, Object> azureEventHub = (Map<String, Object>) root.get("azureEventHub");

        if (azureEventHub == null) {
            throw new IllegalArgumentException("YAML file missing 'azureEventHub' root key");
        }

        if (azureEventHub.containsKey("instances")) {
            return loadMultiInstance(mapper, azureEventHub);
        } else {
            return loadLegacySingleInstance(mapper, azureEventHub);
        }
    }

    /**
     * Load configurations from TAK Server's config object (legacy format only).
     * Used as a fallback when the YAML file cannot be located for direct parsing.
     */
    public static List<EventHubSenderInstanceConfig> loadFromTakConfig(Object config) {
        try {
            java.lang.reflect.Method containsProperty = config.getClass().getMethod("containsProperty", String.class);
            java.lang.reflect.Method getProperty = config.getClass().getMethod("getProperty", String.class);

            // Check if this is multi-instance by looking for the instances marker
            if ((boolean) containsProperty.invoke(config, "azureEventHub.instances[0].namespace")) {
                return loadMultiInstanceFromTakConfig(config, containsProperty, getProperty);
            }

            // Legacy single-instance format
            EventHubSenderInstanceConfig instance = loadSingleInstanceFromTakConfig(config, containsProperty, getProperty);
            return Collections.singletonList(instance);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load configuration from TAK Server config object", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<EventHubSenderInstanceConfig> loadMultiInstance(ObjectMapper mapper, Map<String, Object> azureEventHub) throws IOException {
        List<Map<String, Object>> instanceMaps = (List<Map<String, Object>>) azureEventHub.get("instances");

        if (instanceMaps == null || instanceMaps.isEmpty()) {
            throw new IllegalArgumentException("'instances' list is empty — at least one instance is required");
        }

        List<EventHubSenderInstanceConfig> configs = new ArrayList<>();
        for (int i = 0; i < instanceMaps.size(); i++) {
            Map<String, Object> instanceMap = instanceMaps.get(i);
            String json = mapper.writeValueAsString(instanceMap);
            EventHubSenderInstanceConfig config = mapper.readValue(json, EventHubSenderInstanceConfig.class);

            if (config.getName() == null || config.getName().trim().isEmpty()) {
                config.setName("instance-" + i);
            }

            config.validate();
            configs.add(config);
        }

        logger.info("Loaded {} Event Hub sender instance configuration(s) (multi-instance format)", configs.size());
        return configs;
    }

    private static List<EventHubSenderInstanceConfig> loadLegacySingleInstance(ObjectMapper mapper, Map<String, Object> azureEventHub) throws IOException {
        logger.info("Detected legacy single-instance YAML format — wrapping as single instance");

        String json = mapper.writeValueAsString(azureEventHub);
        EventHubSenderInstanceConfig config = mapper.readValue(json, EventHubSenderInstanceConfig.class);

        if (config.getName() == null || config.getName().trim().isEmpty()) {
            config.setName("default");
        }

        config.validate();
        return Collections.singletonList(config);
    }

    private static List<EventHubSenderInstanceConfig> loadMultiInstanceFromTakConfig(
            Object config,
            java.lang.reflect.Method containsProperty,
            java.lang.reflect.Method getProperty) throws Exception {

        logger.warn("Multi-instance config via TAK Server config API is limited. Consider using direct YAML file.");
        List<EventHubSenderInstanceConfig> instances = new ArrayList<>();
        int index = 0;

        while ((boolean) containsProperty.invoke(config, "azureEventHub.instances[" + index + "].namespace")) {
            String prefix = "azureEventHub.instances[" + index + "].";
            EventHubSenderInstanceConfig instance = buildInstanceFromTakConfig(config, getProperty, prefix, "instance-" + index);
            instance.validate();
            instances.add(instance);
            index++;
        }

        if (instances.isEmpty()) {
            throw new IllegalArgumentException("No valid instances found in configuration");
        }

        logger.info("Loaded {} Event Hub sender instance configuration(s) from TAK config", instances.size());
        return instances;
    }

    private static EventHubSenderInstanceConfig loadSingleInstanceFromTakConfig(
            Object config,
            java.lang.reflect.Method containsProperty,
            java.lang.reflect.Method getProperty) throws Exception {

        String prefix = "azureEventHub.";
        EventHubSenderInstanceConfig instance = buildInstanceFromTakConfig(config, getProperty, prefix, "default");
        instance.validate();

        logger.info("Loaded single Event Hub sender instance configuration from TAK config (legacy format)");
        return instance;
    }

    private static EventHubSenderInstanceConfig buildInstanceFromTakConfig(
            Object config,
            java.lang.reflect.Method getProperty,
            String prefix,
            String defaultName) throws Exception {

        EventHubSenderInstanceConfig instance = new EventHubSenderInstanceConfig();
        instance.setName(getStringProp(config, getProperty, prefix + "name", defaultName));
        instance.setNamespace(getStringProp(config, getProperty, prefix + "namespace", null));
        instance.setEventHubName(getStringProp(config, getProperty, prefix + "eventHubName", null));

        EventHubSenderInstanceConfig.AuthenticationConfig auth = new EventHubSenderInstanceConfig.AuthenticationConfig();
        auth.setTenantId(getStringProp(config, getProperty, prefix + "authentication.tenantId", null));
        auth.setClientId(getStringProp(config, getProperty, prefix + "authentication.clientId", null));
        auth.setClientSecret(getStringProp(config, getProperty, prefix + "authentication.clientSecret", null));
        auth.setAuthorityHost(getStringProp(config, getProperty, prefix + "authentication.authorityHost", null));
        instance.setAuthentication(auth);

        EventHubSenderInstanceConfig.ProcessingConfig processing = new EventHubSenderInstanceConfig.ProcessingConfig();
        processing.setThreadPoolSize(getStringProp(config, getProperty, prefix + "processing.threadPoolSize", null));
        processing.setPartitionStrategy(getStringProp(config, getProperty, prefix + "processing.partitionStrategy", null));
        processing.setPartitionKey(getStringProp(config, getProperty, prefix + "processing.partitionKey", null));
        processing.setPartitionId(getStringProp(config, getProperty, prefix + "processing.partitionId", null));
        processing.setBatchingEnabled(getStringProp(config, getProperty, prefix + "processing.batchingEnabled", null));
        processing.setBatchFlushIntervalMs(getStringProp(config, getProperty, prefix + "processing.batchFlushIntervalMs", null));
        processing.setBatchMaxSize(getStringProp(config, getProperty, prefix + "processing.batchMaxSize", null));

        EventHubSenderInstanceConfig.RetryConfig retry = new EventHubSenderInstanceConfig.RetryConfig();
        retry.setMaxRetries(getStringProp(config, getProperty, prefix + "processing.retry.maxRetries", null));
        retry.setDelaySeconds(getStringProp(config, getProperty, prefix + "processing.retry.delaySeconds", null));
        retry.setMaxDelaySeconds(getStringProp(config, getProperty, prefix + "processing.retry.maxDelaySeconds", null));
        retry.setTimeoutMinutes(getStringProp(config, getProperty, prefix + "processing.retry.timeoutMinutes", null));
        processing.setRetry(retry);

        instance.setProcessing(processing);

        EventHubSenderInstanceConfig.MessageProcessingConfig msgProcessing = new EventHubSenderInstanceConfig.MessageProcessingConfig();
        msgProcessing.setValidateXml(getStringProp(config, getProperty, prefix + "messageProcessing.validateXml", null));
        msgProcessing.setLogMessageDetails(getStringProp(config, getProperty, prefix + "messageProcessing.logMessageDetails", null));
        msgProcessing.setMaxMessageSizeKB(getStringProp(config, getProperty, prefix + "messageProcessing.maxMessageSizeKB", null));
        msgProcessing.setCompressMessages(getStringProp(config, getProperty, prefix + "messageProcessing.compressMessages", null));
        instance.setMessageProcessing(msgProcessing);

        EventHubSenderInstanceConfig.MetricsConfig metricsConfig = new EventHubSenderInstanceConfig.MetricsConfig();
        metricsConfig.setEnabled(getStringProp(config, getProperty, prefix + "metrics.enabled", null));
        metricsConfig.setIntervalMinutes(getStringProp(config, getProperty, prefix + "metrics.intervalMinutes", null));
        instance.setMetrics(metricsConfig);

        EventHubSenderInstanceConfig.TakServerConfig takServerConfig = new EventHubSenderInstanceConfig.TakServerConfig();
        takServerConfig.setSourceGroups(getStringProp(config, getProperty, prefix + "takServer.sourceGroups", "__ANON__"));
        instance.setTakServer(takServerConfig);

        return instance;
    }

    private static String getStringProp(Object config, java.lang.reflect.Method getProperty, String key, String defaultValue) {
        try {
            Object value = getProperty.invoke(config, key);
            return value != null ? (String) value : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
