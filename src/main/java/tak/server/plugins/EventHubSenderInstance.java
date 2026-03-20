package tak.server.plugins;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.core.credential.TokenCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.azure.messaging.eventhubs.models.CreateBatchOptions;
import com.azure.messaging.eventhubs.models.SendOptions;

/**
 * Manages the lifecycle of a single Event Hub sender instance.
 * Each instance has its own producer client, thread pool, and health tracking.
 * Supports both individual and batch send modes.
 */
public class EventHubSenderInstance {

    private final Logger logger;
    private final EventHubSenderInstanceConfig config;

    private EventHubProducerClient producerClient;
    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutorService;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isHealthy = new AtomicBoolean(false);

    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong messagesFailedToSend = new AtomicLong(0);
    private volatile String lastError;
    private volatile long lastErrorTimestamp;

    // Batch mode fields
    private volatile EventDataBatch currentBatch;
    private final ReentrantLock batchLock = new ReentrantLock();
    private final AtomicInteger batchEventCount = new AtomicInteger(0);

    public EventHubSenderInstance(EventHubSenderInstanceConfig config) {
        this.config = config;
        this.logger = LoggerFactory.getLogger(EventHubSenderInstance.class.getName() + "." + config.getDisplayName());
    }

    public void start() {
        try {
            logger.info("[{}] Starting Event Hub sender instance", config.getDisplayName());

            executorService = Executors.newFixedThreadPool(config.getProcessing().getThreadPoolSize());
            scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

            TokenCredential credential = new ClientSecretCredentialBuilder()
                    .tenantId(config.getAuthentication().getTenantId())
                    .clientId(config.getAuthentication().getClientId())
                    .clientSecret(config.getAuthentication().getClientSecret())
                    .authorityHost(config.getAuthentication().getAuthorityHost())
                    .build();

            AmqpRetryOptions retryOptions = new AmqpRetryOptions()
                    .setMaxRetries(config.getProcessing().getRetry().getMaxRetries())
                    .setDelay(Duration.ofSeconds(config.getProcessing().getRetry().getDelaySeconds()))
                    .setMaxDelay(Duration.ofSeconds(config.getProcessing().getRetry().getMaxDelaySeconds()))
                    .setTryTimeout(Duration.ofMinutes(config.getProcessing().getRetry().getTimeoutMinutes()));

            producerClient = new EventHubClientBuilder()
                    .fullyQualifiedNamespace(config.getNamespace())
                    .eventHubName(config.getEventHubName())
                    .credential(credential)
                    .retryOptions(retryOptions)
                    .buildProducerClient();

            if (config.getProcessing().isBatchingEnabled()) {
                currentBatch = createNewBatch();
                scheduledExecutorService.scheduleWithFixedDelay(
                        this::flushBatch,
                        config.getProcessing().getBatchFlushIntervalMs(),
                        config.getProcessing().getBatchFlushIntervalMs(),
                        TimeUnit.MILLISECONDS);
                logger.info("[{}] Batch mode enabled — flush interval: {}ms, max batch size: {}",
                        config.getDisplayName(),
                        config.getProcessing().getBatchFlushIntervalMs(),
                        config.getProcessing().getBatchMaxSize());
            }

            if (config.getMetrics() != null && config.getMetrics().isEnabled()) {
                scheduledExecutorService.scheduleWithFixedDelay(
                        this::logMetrics,
                        config.getMetrics().getIntervalMinutes(),
                        config.getMetrics().getIntervalMinutes(),
                        TimeUnit.MINUTES);
            }

            isRunning.set(true);
            isHealthy.set(true);

            logger.info("[{}] Started — Namespace: {}, Event Hub: {}, Partition Strategy: {}, Threads: {}",
                    config.getDisplayName(),
                    config.getNamespace(),
                    config.getEventHubName(),
                    config.getProcessing().getPartitionStrategy(),
                    config.getProcessing().getThreadPoolSize());

        } catch (Exception e) {
            logger.error("[{}] Failed to start: {}", config.getDisplayName(), e.getMessage(), e);
            isHealthy.set(false);
            lastError = e.getMessage();
            lastErrorTimestamp = System.currentTimeMillis();
        }
    }

    private EventDataBatch createNewBatch() {
        CreateBatchOptions options = new CreateBatchOptions();
        String strategy = config.getProcessing().getPartitionStrategy();

        if ("partitionKey".equals(strategy)) {
            options.setPartitionKey(config.getProcessing().getPartitionKey());
        } else if ("partitionId".equals(strategy)) {
            options.setPartitionId(config.getProcessing().getPartitionId());
        }
        // "roundRobin" or default: no partition option — Event Hubs distributes automatically

        return producerClient.createBatch(options);
    }

    public void sendMessage(String cotXml) {
        if (!isRunning.get()) {
            logger.warn("[{}] Cannot send — instance is not running", config.getDisplayName());
            return;
        }

        if (cotXml.getBytes().length > config.getMessageProcessing().getMaxMessageSizeKB() * 1024) {
            logger.warn("[{}] Message exceeds max size ({}KB) — dropping",
                    config.getDisplayName(), config.getMessageProcessing().getMaxMessageSizeKB());
            messagesFailedToSend.incrementAndGet();
            return;
        }

        EventData eventData = new EventData(cotXml);
        eventData.getProperties().put("source", "takserver");
        eventData.getProperties().put("timestamp", Instant.now().toString());
        eventData.getProperties().put("messageType", "cot");
        eventData.getProperties().put("compressed", "false");

        if (config.getMessageProcessing().isLogMessageDetails()) {
            logger.debug("[{}] Preparing to send CoT message ({} bytes)",
                    config.getDisplayName(), cotXml.getBytes().length);
        }

        if (config.getProcessing().isBatchingEnabled()) {
            sendBatch(eventData);
        } else {
            sendIndividual(eventData);
        }
    }

    private void sendBatch(EventData eventData) {
        batchLock.lock();
        try {
            if (!currentBatch.tryAdd(eventData)) {
                // Batch is full — flush and create a new one
                logger.debug("[{}] Batch full — flushing {} events before adding new event",
                        config.getDisplayName(), batchEventCount.get());
                doFlushBatch();
                currentBatch = createNewBatch();
                batchEventCount.set(0);

                if (!currentBatch.tryAdd(eventData)) {
                    logger.error("[{}] Event too large for an empty batch — dropping", config.getDisplayName());
                    messagesFailedToSend.incrementAndGet();
                    return;
                }
            }

            batchEventCount.incrementAndGet();

            if (batchEventCount.get() >= config.getProcessing().getBatchMaxSize()) {
                logger.debug("[{}] Batch max size reached ({}) — flushing",
                        config.getDisplayName(), batchEventCount.get());
                doFlushBatch();
                currentBatch = createNewBatch();
                batchEventCount.set(0);
            }
        } catch (Exception e) {
            logger.error("[{}] Error adding event to batch: {}", config.getDisplayName(), e.getMessage(), e);
            messagesFailedToSend.incrementAndGet();
            lastError = e.getMessage();
            lastErrorTimestamp = System.currentTimeMillis();
        } finally {
            batchLock.unlock();
        }
    }

    private void sendIndividual(EventData eventData) {
        executorService.submit(() -> {
            try {
                SendOptions sendOptions = new SendOptions();
                String strategy = config.getProcessing().getPartitionStrategy();

                if ("partitionKey".equals(strategy)) {
                    sendOptions.setPartitionKey(config.getProcessing().getPartitionKey());
                } else if ("partitionId".equals(strategy)) {
                    sendOptions.setPartitionId(config.getProcessing().getPartitionId());
                }
                // "roundRobin" or default: no partition option

                producerClient.send(Collections.singletonList(eventData), sendOptions);
                messagesSent.incrementAndGet();

                if (config.getMessageProcessing().isLogMessageDetails()) {
                    logger.debug("[{}] Sent individual event to Event Hub", config.getDisplayName());
                }
            } catch (Exception e) {
                messagesFailedToSend.incrementAndGet();
                lastError = e.getMessage();
                lastErrorTimestamp = System.currentTimeMillis();
                isHealthy.set(false);
                logger.error("[{}] Failed to send event: {}", config.getDisplayName(), e.getMessage(), e);
            }
        });
    }

    /**
     * Scheduled batch flush — called periodically and on shutdown.
     */
    private void flushBatch() {
        batchLock.lock();
        try {
            doFlushBatch();
            currentBatch = createNewBatch();
            batchEventCount.set(0);
        } catch (Exception e) {
            logger.error("[{}] Error during scheduled batch flush: {}", config.getDisplayName(), e.getMessage(), e);
            lastError = e.getMessage();
            lastErrorTimestamp = System.currentTimeMillis();
        } finally {
            batchLock.unlock();
        }
    }

    /**
     * Internal flush — must be called while holding batchLock.
     */
    private void doFlushBatch() {
        int count = batchEventCount.get();
        if (count > 0) {
            try {
                producerClient.send(currentBatch);
                messagesSent.addAndGet(count);
                logger.debug("[{}] Flushed batch of {} events", config.getDisplayName(), count);
            } catch (Exception e) {
                messagesFailedToSend.addAndGet(count);
                lastError = e.getMessage();
                lastErrorTimestamp = System.currentTimeMillis();
                isHealthy.set(false);
                logger.error("[{}] Failed to flush batch of {} events: {}",
                        config.getDisplayName(), count, e.getMessage(), e);
            }
        }
    }

    public void stop() {
        logger.info("[{}] Stopping", config.getDisplayName());
        isRunning.set(false);

        // Flush remaining batch
        if (config.getProcessing().isBatchingEnabled()) {
            batchLock.lock();
            try {
                doFlushBatch();
            } catch (Exception e) {
                logger.warn("[{}] Error flushing final batch on shutdown: {}", config.getDisplayName(), e.getMessage());
            } finally {
                batchLock.unlock();
            }
        }

        if (scheduledExecutorService != null) {
            scheduledExecutorService.shutdown();
            try {
                if (!scheduledExecutorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduledExecutorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduledExecutorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduledExecutorService = null;
        }

        if (producerClient != null) {
            try {
                producerClient.close();
            } catch (Exception e) {
                logger.warn("[{}] Error closing producer client: {}", config.getDisplayName(), e.getMessage());
            }
            producerClient = null;
        }

        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                    logger.warn("[{}] Executor did not terminate gracefully, forcing shutdown", config.getDisplayName());
                    executorService.shutdownNow();
                    if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                        logger.error("[{}] Executor did not terminate after forced shutdown", config.getDisplayName());
                    }
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executorService = null;
        }

        isHealthy.set(false);
        logMetrics();
        logger.info("[{}] Stopped", config.getDisplayName());
    }

    private void logMetrics() {
        long sent = messagesSent.get();
        long failed = messagesFailedToSend.get();
        long total = sent + failed;
        double successRate = total > 0 ? (sent * 100.0 / total) : 100.0;

        logger.info("[{}] Metrics — Sent: {}, Failed: {}, Success rate: {}%",
                config.getDisplayName(), sent, failed, String.format("%.1f", successRate));
    }

    // --- Health and status ---

    public boolean isHealthy() { return isHealthy.get(); }
    public boolean isRunning() { return isRunning.get(); }
    public String getDisplayName() { return config.getDisplayName(); }
    public long getMessagesSent() { return messagesSent.get(); }
    public long getMessagesFailed() { return messagesFailedToSend.get(); }
    public String getLastError() { return lastError; }
    public long getLastErrorTimestamp() { return lastErrorTimestamp; }

    public String getHealthSummary() {
        return String.format("[%s] healthy=%s, sent=%d, failed=%d%s",
                config.getDisplayName(),
                isHealthy.get(),
                messagesSent.get(),
                messagesFailedToSend.get(),
                lastError != null ? ", lastError=" + lastError : "");
    }
}
