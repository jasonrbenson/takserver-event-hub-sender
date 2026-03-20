# TAK Server Azure Event Hub Sender

## Overview

The TAK Server Azure Event Hub Sender provides an integration point between TAK Server and Azure Event Hubs. This sender intercepts Cursor-on-Target (CoT) events from TAK Server and publishes them to one or more Azure Event Hub instances for downstream consumption by analytics pipelines, archival systems, or other TAK Server deployments.

The sender utilizes the Azure Event Hubs SDK for Java and supports configurable **partition strategies**, **batch and individual send modes**, and **multiple Event Hub instances** with independent credentials, partition configuration, and source group filtering — each instance runs with its own thread pool and health tracking.

## Features

- **Multi-Instance Support**: Send to multiple Event Hub instances simultaneously with independent configuration
- **Fan-Out Delivery**: Route CoT events from TAK Server to multiple Event Hubs from a single plugin
- **Configurable Partition Strategy**: Round-robin, partition key, or specific partition targeting
- **Batch & Individual Send Modes**: Low-latency individual sends or high-throughput batched delivery
- **Concurrent Processing**: Per-instance thread pools prevent one connection from starving another
- **Failure Isolation**: One instance failing does not affect other instances
- **Health Monitoring**: Periodic metrics logging with per-instance send counts and error tracking
- **Multi-Cloud Support**: Supports Azure Commercial, Government, China, and custom/air-gapped environments
- **Backwards Compatible**: Existing single-instance YAML configurations continue to work without changes

## Prerequisites

### Azure Event Hub Configuration

1. **Azure Event Hub Namespace**: An Event Hubs namespace in any supported Azure cloud
2. **Event Hub**: An Event Hub entity within the namespace
3. **Authentication**: Azure AD application with appropriate permissions

### Azure AD Application Setup

1. Create an Azure AD application in your tenant
2. Generate a client secret for the application
3. Grant the application the following role on the Event Hub namespace:
   - `Azure Event Hubs Data Sender`

### Recommended Event Hub Configuration
```
Event Hub Configuration:
- Partition Count: Based on expected throughput (2–32)
- Message Retention: Based on your requirements (1–90 days)
- Capture: Enable if archival to Azure Blob Storage is needed
- Throughput Units: Based on expected message volume
```

## Sender Configuration

The sender uses YAML-based configuration. It supports two formats:

1. **Multi-instance format** (recommended) — send to multiple Event Hub instances
2. **Legacy single-instance format** (backwards compatible) — send to one instance

The plugin auto-detects which format is in use.

### Configuration File Location

Place the configuration file at:
```
/opt/tak/conf/plugins/tak.server.plugins.AzureEventHubSender.yaml
```

### Multi-Instance Configuration (Recommended)

```yaml
azureEventHub:
  instances:
    - name: "east-region"
      namespace: "eh-east.servicebus.usgovcloudapi.net"
      eventHubName: "cot-events"
      authentication:
        tenantId: "your-tenant-id"
        clientId: "your-client-id"
        clientSecret: "your-client-secret"
        authorityHost: "https://login.microsoftonline.us/"
      processing:
        threadPoolSize: "4"
        partitionStrategy: "roundRobin"
        partitionKey: ""
        partitionId: ""
        batchingEnabled: "false"
        batchFlushIntervalMs: "1000"
        batchMaxSize: "100"
        retry:
          maxRetries: "3"
          delaySeconds: "2"
          maxDelaySeconds: "30"
          timeoutMinutes: "1"
      messageProcessing:
        validateXml: "true"
        logMessageDetails: "false"
        maxMessageSizeKB: "256"
        compressMessages: "false"
      metrics:
        enabled: "true"
        intervalMinutes: "10"
      takServer:
        sourceGroups: "east-tactical"

    - name: "west-region"
      namespace: "eh-west.servicebus.windows.net"
      eventHubName: "cot-events-dev"
      authentication:
        tenantId: "different-tenant-id"
        clientId: "different-client-id"
        clientSecret: "different-client-secret"
        authorityHost: "https://login.microsoftonline.com/"
      processing:
        threadPoolSize: "4"
        partitionStrategy: "partitionKey"
        partitionKey: "takserver-sender"
        partitionId: ""
        batchingEnabled: "true"
        batchFlushIntervalMs: "2000"
        batchMaxSize: "50"
        retry:
          maxRetries: "3"
          delaySeconds: "2"
          maxDelaySeconds: "30"
          timeoutMinutes: "1"
      messageProcessing:
        validateXml: "true"
        logMessageDetails: "false"
        maxMessageSizeKB: "256"
        compressMessages: "false"
      metrics:
        enabled: "true"
        intervalMinutes: "10"
      takServer:
        sourceGroups: "west-ops"
```

Each instance has:
- **Independent credentials** — different tenants, client IDs, and secrets
- **Independent source groups** — filter which TAK Server groups trigger sends to each Event Hub
- **Independent thread pools** — one slow instance won't starve another
- **Independent partition strategy** — different routing per Event Hub
- **Independent health tracking** — per-instance send counters and error logging

### Legacy Single-Instance Configuration

Existing single-instance configs continue to work. The plugin detects the absence of the `instances` key and treats the config as a single instance named "default":

```yaml
azureEventHub:
  namespace: "your-namespace.servicebus.windows.net"
  eventHubName: "cot-events"

  authentication:
    clientId: "your-application-client-id"
    tenantId: "your-azure-tenant-id"
    clientSecret: "your-application-client-secret"
    authorityHost: "https://login.microsoftonline.com/"

  processing:
    threadPoolSize: "4"
    partitionStrategy: "roundRobin"
    partitionKey: ""
    partitionId: ""
    batchingEnabled: "false"
    batchFlushIntervalMs: "1000"
    batchMaxSize: "100"
    retry:
      maxRetries: "3"
      delaySeconds: "2"
      maxDelaySeconds: "30"
      timeoutMinutes: "1"

  messageProcessing:
    validateXml: "true"
    logMessageDetails: "false"
    maxMessageSizeKB: "256"
    compressMessages: "false"

  metrics:
    enabled: "true"
    intervalMinutes: "10"

  takServer:
    sourceGroups: "__ANON__"
```

### Configuration Values Explained

#### Event Hub Settings
- `namespace`: Fully qualified Event Hubs namespace (e.g., `your-namespace.servicebus.windows.net` for Commercial, `your-namespace.servicebus.usgovcloudapi.net` for Government)
- `eventHubName`: Name of the Event Hub entity to send messages to

#### Authentication
- `clientId`: Azure AD application client ID
- `tenantId`: Azure AD tenant ID
- `clientSecret`: Azure AD application client secret
- `authorityHost`: Azure AD authority host URL — see table below

#### Authority Host

Set `authorityHost` to the value matching your Azure environment:

| Azure Environment | Authority Host URL |
|---|---|
| Commercial (Public) | `https://login.microsoftonline.com/` |
| US Government | `https://login.microsoftonline.us/` |
| China (21Vianet) | `https://login.chinacloudapi.cn/` |
| Custom / Air-gapped | Your environment's Azure AD endpoint |

#### Processing Configuration
- `threadPoolSize`: Number of threads for message processing
- `partitionStrategy`: How events are distributed across partitions — see [Partition Strategy](#partition-strategy) section
- `partitionKey`: The key used for consistent hashing when `partitionStrategy` is `"partitionKey"`
- `partitionId`: The specific partition ID when `partitionStrategy` is `"partitionId"`
- `batchingEnabled`: `"true"` to buffer and send events in batches, `"false"` for individual sends
- `batchFlushIntervalMs`: Milliseconds between automatic batch flushes (only used when `batchingEnabled` is `"true"`)
- `batchMaxSize`: Maximum number of events per batch before automatic flush (only used when `batchingEnabled` is `"true"`)

#### Retry Configuration
- `maxRetries`: Maximum retry attempts for failed operations
- `delaySeconds`: Initial delay between retries
- `maxDelaySeconds`: Maximum delay between retries (exponential backoff)
- `timeoutMinutes`: Timeout for individual operations

#### Message Processing
- `validateXml`: Validate XML format before sending
- `logMessageDetails`: Log detailed message content (for debugging)
- `maxMessageSizeKB`: Maximum message size in kilobytes (Event Hubs limit is 1024 KB for Standard tier)
- `compressMessages`: Compress message payloads before sending

#### Metrics
- `enabled`: Enable periodic metrics logging
- `intervalMinutes`: Interval between metrics log entries

#### TAK Server Integration
- `sourceGroups`: Comma-separated list of TAK Server groups to listen for messages from (e.g., `"GROUP1,GROUP2,__ANON__"`)

### Configuration Defaults

All configuration values except `sourceGroups` are **required** and must be provided in the YAML file. If `sourceGroups` is omitted, it defaults to `"__ANON__"` (anonymous users group).

### Source Group Filtering

The `sourceGroups` setting allows you to specify which TAK Server groups' messages should be forwarded to the Event Hub:

```yaml
takServer:
  sourceGroups: "BLUE_TEAM,RED_TEAM,OBSERVERS"
```

Only CoT events from users in the specified groups will be sent. Use `__ANON__` for anonymous/unauthenticated users.

## Partition Strategy

Azure Event Hubs use partitions to enable parallel processing by downstream consumers. The sender supports three strategies:

### Round Robin (`roundRobin`)

Events are distributed across all partitions evenly by the Event Hubs service. This is the default and recommended strategy for most use cases.

```yaml
processing:
  partitionStrategy: "roundRobin"
```

- Best for: Even load distribution, maximum throughput
- Ordering: No ordering guarantees across partitions

### Partition Key (`partitionKey`)

Events with the same partition key are consistently routed to the same partition via hashing. Use this when downstream consumers need to process related events together.

```yaml
processing:
  partitionStrategy: "partitionKey"
  partitionKey: "takserver-east"
```

- Best for: Ordered processing of related events, consumer affinity
- Ordering: Guaranteed within the same partition key

### Partition ID (`partitionId`)

All events are sent to a specific partition by ID. Use this for targeted delivery or testing.

```yaml
processing:
  partitionStrategy: "partitionId"
  partitionId: "0"
```

- Best for: Testing, specific partition targeting
- Ordering: Guaranteed (all events go to one partition)
- Caution: Creates a hot partition — not recommended for production

## Batch Mode

When `batchingEnabled` is `"true"`, the sender buffers outgoing events and sends them in batches for improved throughput.

### How It Works

1. Incoming CoT events are added to a per-instance buffer
2. A batch is flushed automatically when either condition is met:
   - The buffer reaches `batchMaxSize` events
   - The `batchFlushIntervalMs` timer expires
3. Each batch is sent as a single `EventDataBatch` to Azure Event Hubs

### Configuration

```yaml
processing:
  batchingEnabled: "true"
  batchFlushIntervalMs: "1000"   # Flush every 1 second
  batchMaxSize: "100"            # Or when 100 events accumulate
```

### Trade-offs

| Mode | Latency | Throughput | Cost Efficiency |
|---|---|---|---|
| Individual (`"false"`) | Low (~ms) | Lower | Lower (more API calls) |
| Batch (`"true"`) | Higher (~seconds) | Higher | Higher (fewer API calls) |

## Building the Sender

### Prerequisites
- Java 11 or higher
- Gradle 7.x

### Build Commands

```bash
# Clean and build
./gradlew clean build

# Build shadow JAR (includes all dependencies)
./gradlew shadowJar

# Both regular and shadow JAR
./gradlew build shadowJar
```

### Build Outputs

- `build/libs/takserver-event-hub-sender.jar` - Sender code only
- `build/libs/takserver-event-hub-sender-all.jar` - Sender + all dependencies (deploy this one)

## Installation

1. **Build the shadow JAR**: `./gradlew clean build shadowJar`

2. **Copy the JAR**: Copy `build/libs/takserver-event-hub-sender-all.jar` to `/opt/tak/lib/` on your TAK Server

   > **Note**: If you have other plugins that bundle the same Azure SDK libraries (e.g., Event Hub Receiver, Service Bus Receiver), verify there are no classpath collisions. The shadow JAR relocates dependencies to minimize conflicts, but in some environments you may need to use the non-shadow JAR and manage shared dependencies externally.

3. **Create configuration**: Create the YAML config file at `/opt/tak/conf/plugins/tak.server.plugins.AzureEventHubSender.yaml`

4. **Configure the sender**: Edit the YAML file with your Azure Event Hub and authentication settings

5. **Restart TAK Server**: `sudo systemctl restart takserver`

6. **Verify**: Check `/opt/tak/logs/takserver-plugins.log` for successful startup messages

## Message Format

The sender publishes XML-formatted Cursor-on-Target (CoT) messages to Event Hubs. Each event includes:

### Event Body

The CoT XML string as the event body:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<event version="2.0" uid="unique-id" type="a-f-G-U-C" time="2023-12-01T12:00:00.000Z" start="2023-12-01T12:00:00.000Z" stale="2023-12-01T12:30:00.000Z">
    <point lat="40.7128" lon="-74.0060" hae="10.0" ce="10.0" le="10.0"/>
    <detail>
        <contact callsign="Example Unit"/>
    </detail>
</event>
```

### Application Properties

Each event includes metadata as application properties:

| Property | Description | Example |
|---|---|---|
| `contentType` | MIME type of the body | `application/xml` |
| `source` | Originating plugin name | `AzureEventHubSender` |
| `instanceName` | Sending instance name | `east-region` |
| `cotType` | CoT event type | `a-f-G-U-C` |
| `cotUid` | CoT event UID | `unique-id` |
| `sentAt` | ISO 8601 timestamp | `2023-12-01T12:00:00.000Z` |

## Performance Considerations

- **Thread pool sizing**: Set `threadPoolSize` based on expected message throughput. Start with `4` and increase if send queues back up.
- **Batch mode**: Enable batching for high-volume scenarios (>100 events/second) to reduce API call overhead.
- **Partition count**: Ensure your Event Hub has enough partitions for your throughput needs. More partitions enable more parallel consumers downstream.
- **Max message size**: The default `maxMessageSizeKB: "256"` is well within Event Hubs Standard tier limits (1024 KB). Increase only if sending large CoT events with extensive detail sections.
- **Retry configuration**: The default retry settings (3 retries, 2s initial delay, 30s max delay) handle transient failures. Increase `maxRetries` for unreliable network conditions.

## Troubleshooting

### Common Issues

1. **Authentication Failures**
   - Verify client ID, tenant ID, and client secret
   - Ensure `authorityHost` matches your Azure environment (see Authority Host table above)
   - Ensure Azure AD application has the `Azure Event Hubs Data Sender` role
   - Check if application secret has expired

2. **Messages Not Being Sent**
   - Verify `sourceGroups` matches the TAK Server groups generating events
   - Check Event Hub namespace and Event Hub name are correct
   - Monitor Azure Event Hub metrics for incoming messages
   - Enable `logMessageDetails: "true"` temporarily for debugging

3. **Throughput Issues**
   - Enable batch mode for high-volume scenarios
   - Increase `threadPoolSize` if processing backs up
   - Verify Event Hub throughput units are sufficient
   - Check partition count — more partitions allow more parallel sends

4. **SSL Certificate Issues**
   - Verify the Event Hub endpoint matches your Azure environment (e.g., `*.servicebus.windows.net` for Commercial, `*.servicebus.usgovcloudapi.net` for Government)
   - Check network connectivity to your Event Hub namespace

5. **Large Message Failures**
   - Check `maxMessageSizeKB` setting against actual CoT event sizes
   - Event Hubs Standard tier supports up to 1024 KB per event
   - Enable `compressMessages: "true"` if events are near the size limit

### Logging

The sender provides detailed logging at various levels:

- **INFO**: Connection status, send summaries, metrics reports
- **DEBUG**: Individual message details, partition assignments, batch flushes
- **ERROR**: Connection failures, authentication issues, send errors

### Monitoring

Monitor the following metrics:

- TAK Server sender logs for processing status and periodic metrics
- Azure Event Hub metrics (incoming messages, throughput, errors)
- System resource usage (CPU, memory, threads)
- Per-instance send counts and error rates in plugin logs

## Integration with Event Hub Receiver

The sender is designed to work with the [TAK Server Azure Event Hub Receiver](https://github.com/jasonrbenson/takserver-event-hub-receiver) plugin. A common deployment pattern:

```
TAK Server A → Event Hub Sender → Azure Event Hub → Event Hub Receiver → TAK Server B
```

This enables:
- **Cross-network CoT sharing**: Bridge CoT events between isolated TAK Server deployments
- **Fan-out distribution**: One sender, multiple receivers on different TAK Servers
- **Cloud-based event routing**: Leverage Azure Event Hubs as a scalable message broker

When paired with the receiver, the application properties set by the sender (e.g., `cotType`, `cotUid`, `instanceName`) are available to the receiver for filtering and routing.

## Version Compatibility

| Sender Version | TAK Server | Java | Azure Event Hubs SDK | Azure Identity SDK |
|---|---|---|---|---|
| 1.0.0 | 5.2+ | 11+ | 5.21.3 | 1.15.3 |
