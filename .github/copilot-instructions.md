# Copilot Instructions

## Project Overview

TAK Server Azure Event Hub Sender — a TAK Server plugin that sends Cursor-on-Target (CoT) XML messages to one or more Azure Event Hubs. Supports multi-instance fan-out, configurable partition strategies (round-robin, partition key, specific partition), batch and individual send modes, multiple Azure cloud environments (Commercial, Government, China, air-gapped), and health monitoring with periodic metrics logging.

## Build

```bash
# Build requires credentials for the TAK Server artifacts repository (artifacts.tak.gov).
# Provide either takGovApiKey or takGovUser/takGovPassword in gradle.properties.

# Clean build + shadow JAR (the deployable artifact)
./gradlew clean build shadowJar

# The shadow JAR (with all dependencies) is the deployment artifact:
#   build/libs/takserver-event-hub-sender-all.jar
```

Java 11 source/target compatibility. The CI uses JDK 17 to run the build.

There are no tests in this project.

## Architecture

This is a **TAK Server plugin** — a sender that bridges TAK Server → Azure Event Hubs.

- `AzureEventHubSender` extends `MessageSenderReceiverBase` (from the TAK Server Plugin API) and is annotated with `@TakServerPlugin`.
- On `start()`, it loads YAML configuration via TAK Server's `config` object, then creates one or more `EventHubProducerClient` instances — one per configured Event Hub instance.
- Outgoing messages are XML Cursor-on-Target (CoT) events received from TAK Server. They are converted via `getConverter().dataMessageToCotString()` and sent to Azure Event Hubs.
- Supports three partition strategies: `roundRobin` (default), `partitionKey` (consistent hashing), and `partitionId` (specific partition targeting).
- Supports batch mode (buffered sends with configurable flush interval and max batch size) and individual send mode.
- Per-instance health metrics with periodic logging of send counts, error counts, and throughput.

## Key Conventions

- **All configuration is externalized to YAML** (`tak.server.plugins.AzureEventHubSender.yaml`). Every config value except `sourceGroups` is required — no defaults in code. The example config lives at `conf/plugins/tak.server.plugins.AzureEventHubSender.yaml.example`.
- **Config values are read as strings** and parsed with `Integer.parseInt()` / `Boolean.parseBoolean()` — maintain this pattern when adding new config properties.
- **Sensitive files are gitignored** — real YAML config, `.p12` certs, and `.pem` files must not be committed. Only `.example`/`.sample`/`.template` files are tracked.
- **Shadow JAR is the deployment artifact** — `takserver-event-hub-sender-all.jar` bundles all dependencies for drop-in deployment to a TAK Server plugins directory.
- The TAK Server Plugin API dependency (`gov.tak:takserver-plugins`) is `compileOnly` (provided at runtime by TAK Server) and `testImplementation`. It is fetched from `https://artifacts.tak.gov/artifactory/maven`.
