package tak.server.plugins;

import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configuration POJO for a single Event Hub sender instance.
 * Deserialized from YAML via Jackson.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventHubSenderInstanceConfig {

    @JsonProperty("name")
    private String name;

    @JsonProperty("namespace")
    private String namespace;

    @JsonProperty("eventHubName")
    private String eventHubName;

    @JsonProperty("authentication")
    private AuthenticationConfig authentication;

    @JsonProperty("processing")
    private ProcessingConfig processing;

    @JsonProperty("messageProcessing")
    private MessageProcessingConfig messageProcessing;

    @JsonProperty("metrics")
    private MetricsConfig metrics;

    @JsonProperty("takServer")
    private TakServerConfig takServer;

    // --- Nested config classes ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthenticationConfig {
        @JsonProperty("tenantId")
        private String tenantId;

        @JsonProperty("clientId")
        private String clientId;

        @JsonProperty("clientSecret")
        private String clientSecret;

        @JsonProperty("authorityHost")
        private String authorityHost;

        public String getTenantId() { return tenantId; }
        public String getClientId() { return clientId; }
        public String getClientSecret() { return clientSecret; }
        public String getAuthorityHost() { return authorityHost; }

        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public void setAuthorityHost(String authorityHost) { this.authorityHost = authorityHost; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RetryConfig {
        @JsonProperty("maxRetries")
        private String maxRetries;

        @JsonProperty("delaySeconds")
        private String delaySeconds;

        @JsonProperty("maxDelaySeconds")
        private String maxDelaySeconds;

        @JsonProperty("timeoutMinutes")
        private String timeoutMinutes;

        public int getMaxRetries() { return Integer.parseInt(maxRetries); }
        public int getDelaySeconds() { return Integer.parseInt(delaySeconds); }
        public int getMaxDelaySeconds() { return Integer.parseInt(maxDelaySeconds); }
        public int getTimeoutMinutes() { return Integer.parseInt(timeoutMinutes); }

        public void setMaxRetries(String maxRetries) { this.maxRetries = maxRetries; }
        public void setDelaySeconds(String delaySeconds) { this.delaySeconds = delaySeconds; }
        public void setMaxDelaySeconds(String maxDelaySeconds) { this.maxDelaySeconds = maxDelaySeconds; }
        public void setTimeoutMinutes(String timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProcessingConfig {
        @JsonProperty("threadPoolSize")
        private String threadPoolSize;

        @JsonProperty("partitionStrategy")
        private String partitionStrategy;

        @JsonProperty("partitionKey")
        private String partitionKey;

        @JsonProperty("partitionId")
        private String partitionId;

        @JsonProperty("batchingEnabled")
        private String batchingEnabled;

        @JsonProperty("batchFlushIntervalMs")
        private String batchFlushIntervalMs;

        @JsonProperty("batchMaxSize")
        private String batchMaxSize;

        @JsonProperty("retry")
        private RetryConfig retry;

        public int getThreadPoolSize() { return Integer.parseInt(threadPoolSize); }
        public String getPartitionStrategy() { return partitionStrategy; }
        public String getPartitionKey() { return partitionKey; }
        public String getPartitionId() { return partitionId; }
        public boolean isBatchingEnabled() { return Boolean.parseBoolean(batchingEnabled); }
        public int getBatchFlushIntervalMs() {
            return batchFlushIntervalMs != null ? Integer.parseInt(batchFlushIntervalMs) : 1000;
        }
        public int getBatchMaxSize() {
            return batchMaxSize != null ? Integer.parseInt(batchMaxSize) : 100;
        }
        public RetryConfig getRetry() { return retry; }

        public void setThreadPoolSize(String threadPoolSize) { this.threadPoolSize = threadPoolSize; }
        public void setPartitionStrategy(String partitionStrategy) { this.partitionStrategy = partitionStrategy; }
        public void setPartitionKey(String partitionKey) { this.partitionKey = partitionKey; }
        public void setPartitionId(String partitionId) { this.partitionId = partitionId; }
        public void setBatchingEnabled(String batchingEnabled) { this.batchingEnabled = batchingEnabled; }
        public void setBatchFlushIntervalMs(String batchFlushIntervalMs) { this.batchFlushIntervalMs = batchFlushIntervalMs; }
        public void setBatchMaxSize(String batchMaxSize) { this.batchMaxSize = batchMaxSize; }
        public void setRetry(RetryConfig retry) { this.retry = retry; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MessageProcessingConfig {
        @JsonProperty("validateXml")
        private String validateXml;

        @JsonProperty("logMessageDetails")
        private String logMessageDetails;

        @JsonProperty("maxMessageSizeKB")
        private String maxMessageSizeKB;

        @JsonProperty("compressMessages")
        private String compressMessages;

        public boolean isValidateXml() { return Boolean.parseBoolean(validateXml); }
        public boolean isLogMessageDetails() { return Boolean.parseBoolean(logMessageDetails); }
        public int getMaxMessageSizeKB() { return Integer.parseInt(maxMessageSizeKB); }
        public boolean isCompressMessages() { return Boolean.parseBoolean(compressMessages); }

        public void setValidateXml(String validateXml) { this.validateXml = validateXml; }
        public void setLogMessageDetails(String logMessageDetails) { this.logMessageDetails = logMessageDetails; }
        public void setMaxMessageSizeKB(String maxMessageSizeKB) { this.maxMessageSizeKB = maxMessageSizeKB; }
        public void setCompressMessages(String compressMessages) { this.compressMessages = compressMessages; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MetricsConfig {
        @JsonProperty("enabled")
        private String enabled;

        @JsonProperty("intervalMinutes")
        private String intervalMinutes;

        public boolean isEnabled() { return Boolean.parseBoolean(enabled); }
        public int getIntervalMinutes() { return Integer.parseInt(intervalMinutes); }

        public void setEnabled(String enabled) { this.enabled = enabled; }
        public void setIntervalMinutes(String intervalMinutes) { this.intervalMinutes = intervalMinutes; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TakServerConfig {
        @JsonProperty("sourceGroups")
        private String sourceGroups;

        public String getSourceGroups() { return sourceGroups; }
        public void setSourceGroups(String sourceGroups) { this.sourceGroups = sourceGroups; }

        /**
         * Parse the comma-separated source groups into a Set.
         * Defaults to __ANON__ if not configured.
         */
        public Set<String> getSourceGroupsAsSet() {
            if (sourceGroups == null || sourceGroups.trim().isEmpty()) {
                return Set.of("__ANON__");
            }
            Set<String> groups = Set.of(sourceGroups.split(",")).stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
            return groups.isEmpty() ? Set.of("__ANON__") : groups;
        }
    }

    // --- Top-level getters/setters ---

    public String getName() { return name; }
    public String getNamespace() { return namespace; }
    public String getEventHubName() { return eventHubName; }
    public AuthenticationConfig getAuthentication() { return authentication; }
    public ProcessingConfig getProcessing() { return processing; }
    public MessageProcessingConfig getMessageProcessing() { return messageProcessing; }
    public MetricsConfig getMetrics() { return metrics; }
    public TakServerConfig getTakServer() { return takServer; }

    public void setName(String name) { this.name = name; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public void setEventHubName(String eventHubName) { this.eventHubName = eventHubName; }
    public void setAuthentication(AuthenticationConfig authentication) { this.authentication = authentication; }
    public void setProcessing(ProcessingConfig processing) { this.processing = processing; }
    public void setMessageProcessing(MessageProcessingConfig messageProcessing) { this.messageProcessing = messageProcessing; }
    public void setMetrics(MetricsConfig metrics) { this.metrics = metrics; }
    public void setTakServer(TakServerConfig takServer) { this.takServer = takServer; }

    /**
     * Returns a display-friendly name for this instance (for logging).
     * Falls back to namespace + eventHubName if name is not set.
     */
    public String getDisplayName() {
        if (name != null && !name.trim().isEmpty()) {
            return name;
        }
        return namespace + "/" + eventHubName;
    }

    /**
     * Validate that all required configuration fields are present.
     * @throws IllegalArgumentException if any required field is missing
     */
    public void validate() {
        String prefix = "[" + getDisplayName() + "] ";

        if (namespace == null || namespace.trim().isEmpty()) {
            throw new IllegalArgumentException(prefix + "namespace is required");
        }
        if (eventHubName == null || eventHubName.trim().isEmpty()) {
            throw new IllegalArgumentException(prefix + "eventHubName is required");
        }

        if (authentication == null) {
            throw new IllegalArgumentException(prefix + "authentication section is required");
        }
        if (authentication.getTenantId() == null || authentication.getTenantId().trim().isEmpty()) {
            throw new IllegalArgumentException(prefix + "authentication.tenantId is required");
        }
        if (authentication.getClientId() == null || authentication.getClientId().trim().isEmpty()) {
            throw new IllegalArgumentException(prefix + "authentication.clientId is required");
        }
        if (authentication.getClientSecret() == null || authentication.getClientSecret().trim().isEmpty()) {
            throw new IllegalArgumentException(prefix + "authentication.clientSecret is required");
        }
        if (authentication.getAuthorityHost() == null || authentication.getAuthorityHost().trim().isEmpty()) {
            throw new IllegalArgumentException(prefix + "authentication.authorityHost is required");
        }

        if (processing == null) {
            throw new IllegalArgumentException(prefix + "processing section is required");
        }
        if (processing.getRetry() == null) {
            throw new IllegalArgumentException(prefix + "processing.retry section is required");
        }

        if (messageProcessing == null) {
            throw new IllegalArgumentException(prefix + "messageProcessing section is required");
        }
    }
}
