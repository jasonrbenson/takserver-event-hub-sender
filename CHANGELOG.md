# Changelog

## v1.0.0 — Initial Release

### Overview

Initial release of the **TAK Server Azure Event Hub Sender**, a production-ready plugin that provides seamless integration between TAK Server and Azure Event Hubs. The sender intercepts Cursor-on-Target (CoT) events from TAK Server and publishes them to one or more Azure Event Hub instances for downstream consumption.

### Key Features

**Multi-Instance Support**
- Connect to multiple Azure Event Hub instances simultaneously with independent configuration
- Fan-out CoT events to multiple Event Hubs from a single plugin deployment
- Per-instance credentials, Event Hub names, and source group filtering
- Independent thread pools prevent one connection from starving another
- Failure isolation — one instance failing does not affect others

**Partition Strategy**
- `roundRobin` — distribute events across all partitions evenly (default)
- `partitionKey` — consistent hashing on a configured key for ordered delivery
- `partitionId` — send all events to a specific partition

**Batch & Individual Send Modes**
- Individual send mode for low-latency delivery (default)
- Batch send mode with configurable flush interval (`batchFlushIntervalMs`) and max batch size (`batchMaxSize`)
- Automatic flush on batch size threshold or timer expiration

**Health Monitoring**
- Periodic metrics logging with configurable interval
- Per-instance send counts, error counts, and throughput tracking
- Per-instance error tracking with last error message and timestamp

**Multi-Cloud Support**
- Azure Commercial, Government, China, and custom/air-gapped environments
- Configurable `authorityHost` for Azure AD authentication endpoint

**Plugin Metadata**
- `ver.json` for TAK Server plugin manager version display
- `@TakServerPluginVersion` annotation as fallback

**Configuration**
- Multi-instance YAML format with `instances` list (recommended)
- Legacy single-instance YAML format (backwards compatible)
- Auto-detection of configuration format

### Deployment Artifacts
- `takserver-event-hub-sender-all.jar` — Complete deployment package with all dependencies
- `takserver-event-hub-sender.jar` — Core library only
- Sample configuration files with examples

### Technical Specifications
- **Java Version**: 11+
- **TAK Server Plugin API**: 5.2-release-16
- **Azure Event Hubs SDK**: 5.21.3
- **Azure Identity SDK**: 1.15.3
- **Build System**: Gradle 7.5.1
