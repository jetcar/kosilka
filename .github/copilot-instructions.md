# AI-Native Coding Instructions

These instructions define how code should be written in this repository so it is:
- easy for humans to read,
- easy for AI agents to understand,
- safe to refactor with automated tools.

## Primary goals

1. Optimize for clarity over cleverness.
2. Keep changes local and predictable.
3. Make intent explicit in names, structure, and types.
4. Prefer small, composable units over large multi-purpose blocks.

## Core principles

### 1) Self-describing code
- Use descriptive names for files, modules, functions, variables, and types.
- Prefer domain language over generic terms (`orderTotal` not `value`, `userSession` not `data`).
- Avoid abbreviations unless they are industry-standard (`id`, `url`, `http`).

### 2) Small, focused units
- One function should do one thing.
- Target function length: 5-20 lines when practical.
- Split large files by responsibility before they become hard to navigate.

### 3) Explicit contracts
- Define clear function inputs/outputs.
- Validate inputs near boundaries (API, CLI, file I/O).
- Use typed interfaces/schemas where available.
- Avoid hidden side effects.

### 4) Predictable control flow
- Prefer early returns over nested conditionals.
- Keep nesting shallow (target max 2-3 levels).
- Avoid mixed sync/async patterns in the same flow.

### 5) Stable structure
- Follow this layering where relevant:
  - `routes/controllers` -> `services/use-cases` -> `repositories/data` -> `models/types`
- Keep business logic out of transport/framework layers.
- Keep framework wiring thin.

### 6) AI-editable code patterns
- Favor pure functions for business rules.
- Keep dependencies injected instead of created deep inside functions.
- Use small wrappers/adapters for external systems.
- Keep each module cohesive and independently testable.

## Style rules

### Naming
- Functions: `verbNoun` (`createInvoice`, `validatePayload`).
- Booleans: `is/has/can` prefix (`isActive`, `hasAccess`).
- Constants: named values instead of magic literals.

### Comments
- Comment *why*, not *what*.
- Add comments only when intent is non-obvious.
- Remove outdated comments immediately.

### Errors
- Fail fast with actionable error messages.
- Never swallow exceptions silently.
- Return/throw consistent error shapes at boundaries.

### Configuration
- No hardcoded environment-specific values.
- Put configuration in env/config files and access through a single config layer.

## Preferred implementation patterns

### Function design
- Keep arguments to 0-3; use an object parameter for more.
- Avoid flag arguments that change behavior dramatically.
- Separate read operations from write operations when possible.

### Data handling
- Normalize data at boundaries.
- Use clear DTOs/interfaces between layers.
- Avoid passing unstructured `any`/raw objects across module boundaries.

### Dependencies
- Prefer stable, well-maintained libraries.
- Minimize dependency count.
- Wrap third-party APIs behind internal interfaces if they are central to business logic.

## Testing requirements
- Unit-test business logic.
- Add integration tests for external boundaries (DB, API, queue, file system).
- Test names should describe behavior, not implementation details.
- Keep tests deterministic and isolated.

## Pull request expectations
- PRs should be small and focused.
- Include a short summary of:
  - what changed,
  - why it changed,
  - risk level,
  - how it was verified.
- Do not mix refactors with feature changes unless necessary.

## Definition of done
A change is complete when:
1. The code is readable without extra explanation.
2. The module boundaries are clear.
3. Error handling is explicit.
4. Tests cover key behavior.
5. Another AI agent can safely modify the code with minimal ambiguity.

## Anti-patterns to avoid
- Long functions with multiple responsibilities.
- Deep nesting and complex branching.
- Hidden side effects and implicit global state.
- Copy-pasted logic across files.
- Vague names (`temp`, `helper`, `data`, `manager`).
- Commented-out dead code.
- Silent catch blocks.

## Android app rules

### Architecture and module boundaries
- Prefer Clean Architecture or feature-based modular architecture.
- Keep Android framework code at the edges; business logic stays in domain/use-case layers.
- UI layer should be state-driven and dumb: render state, emit intents/actions.
- Keep one source of truth per screen state.

### Android implementation standards
- Use Kotlin as default.
- Prefer coroutines + Flow for async streams.
- Use structured concurrency; never launch work without lifecycle-aware scope.
- Keep ViewModel focused on state orchestration, not heavy business logic.
- Define explicit UI state models (`Loading`, `Success`, `Error`, `Empty`) for each screen.
- Keep navigation arguments typed and validated.

### Android reliability and performance
- Do not block main thread; expensive work must be offloaded.
- Use repository pattern for network/storage/hardware bridge.
- Handle offline/timeout/retry behavior explicitly.
- Log with structured tags and error context; avoid noisy logs in release builds.
- Keep battery usage in mind (scan intervals, background work cadence, wake locks).

### Android testing
- Unit-test use cases, reducers, and mappers.
- Add integration tests for repositories and data sources.
- Add UI tests for critical user flows.
- Ensure deterministic tests by mocking clocks, dispatchers, and hardware inputs.

## ESP32 firmware + UWB rules

### Firmware architecture
- Separate hardware drivers, protocol handling, and application logic into distinct modules.
- Keep interrupt service routines minimal: capture signal/state only, defer heavy work to tasks.
- Use explicit state machines for ranging/session lifecycle.
- Avoid hidden global mutable state; centralize shared state with clear ownership.

### Real-time and safety constraints
- Respect timing deadlines for UWB ranging and radio operations.
- Keep critical paths allocation-free; avoid heap allocation in ISR/time-critical loops.
- Define task priorities explicitly and document why each priority is chosen.
- Add watchdog-safe behavior and graceful recovery paths for radio/device faults.

### Memory and power discipline
- Use fixed-size buffers for protocol frames where practical.
- Validate all packet lengths, CRC/checksum, and enum ranges before processing.
- Prevent buffer overflows with strict bounds checks.
- Document and enforce power modes (active, idle, sleep) and transitions.

### Firmware observability and testing
- Use consistent log levels (`ERROR`, `WARN`, `INFO`, `DEBUG`) with module prefix.
- Gate verbose logs behind compile-time/runtime flags.
- Add host-side/unit tests for parsers, serializers, and state machines.
- Add hardware-in-the-loop smoke tests for pairing, ranging start/stop, and reconnection.

## Android <-> ESP32/UWB boundary contract

### Protocol contract rules
- Maintain a versioned message schema for all app-device communication.
- Keep serialization deterministic and backward-compatible when possible.
- Assign stable message IDs and explicit error codes.
- Reject unknown/invalid payloads with explicit error responses.

### Time and session management
- Define session lifecycle states shared by app and firmware.
- Specify timeout and retry policy in one source of truth.
- Use monotonic time references for measurements and timeout calculations.
- Include sequence numbers or request IDs to prevent replay/duplication ambiguity.

### Security baseline
- Require authenticated pairing before accepting control commands.
- Validate integrity of incoming commands/data.
- Never hardcode secrets/keys in source files.
- Keep security-critical operations isolated and code-reviewed.

## Repository structure recommendation for this stack
- `android-app/`: UI, domain, data, platform integrations.
- `firmware-esp32/`: drivers, UWB logic, protocol, tasks.
- `protocol/`: shared message schema docs, IDs, error codes, test vectors.
- `tools/`: scripts for flashing, logs collection, and integration checks.

## Additional done criteria for Android + UWB
1. App and firmware protocol versions are aligned and documented.
2. Critical flows pass: pair, connect, start ranging, stop ranging, reconnect.
3. Timeout/retry behavior is tested on both sides.
4. Logs provide enough context to diagnose failures without reproducing manually.
5. AI agents can change one layer without breaking cross-layer contracts.

## Stack profile: Jetpack Compose + Hilt + Room

### Compose rules
- Use unidirectional data flow: `UI event -> ViewModel intent -> state update -> UI render`.
- Keep composables stateless when possible; hoist state to screen-level/state holder.
- Avoid passing `ViewModel` deep into composable tree; pass state + callbacks.
- Keep composables small and focused; split when a composable handles multiple concerns.
- All UI side effects must use effect APIs (`LaunchedEffect`, `DisposableEffect`, `SideEffect`) with clear keys.

### ViewModel and coroutines
- Inject dependencies via Hilt constructor injection.
- Expose immutable `StateFlow<UiState>` and one-off UI events via separate channel/flow.
- Avoid launching coroutines without explicit dispatcher intent.
- Keep reducer logic pure where practical (event + old state -> new state).

### Room/data layer
- Use `suspend` for one-shot queries and `Flow` for observable queries.
- Explicitly define entity mappers (`Entity <-> Domain <-> Ui`) and do not leak DB entities to UI.
- All schema changes must be migration-backed and versioned.
- Keep DAO methods focused and predictable; avoid hidden joins with surprising performance cost.

### Android package structure recommendation
- `android-app/app/src/main/java/.../feature/<feature>/`: ui, viewmodel, usecase, mapper.
- `android-app/app/src/main/java/.../data/`: repository impl, local (Room), remote/device bridge.
- `android-app/app/src/main/java/.../domain/`: entities, repository contracts, use cases.
- `android-app/app/src/main/java/.../core/`: common utils, error model, dispatcher provider.

## Stack profile: ESP-IDF + FreeRTOS + DW1000/DW3000

### FreeRTOS/task model
- Define fixed tasks with explicit responsibilities (`uwb_task`, `protocol_task`, `sensor_task`, `health_task`).
- Use queues/event groups for communication; avoid ad-hoc shared mutable globals.
- Document and enforce task priority policy and expected loop period.
- Keep watchdog feeding centralized and measurable.

### ESP-IDF coding conventions
- Use `esp_err_t` consistently and check every return value from ESP-IDF APIs.
- Use `ESP_LOGx` with module tags and bounded log frequency for high-rate loops.
- Keep hardware init deterministic and idempotent where possible.
- Put hardware/board constants in a dedicated config header or Kconfig options.

### DW1000/DW3000 UWB specifics
- Isolate transceiver driver from ranging/session orchestration logic.
- Keep antenna delay, channel, preamble, data rate, and STS/security params centralized in config.
- Timestamp handling must use consistent units and monotonic conversion helpers.
- Validate all RX frame lengths and frame control fields before parsing payload.
- On RX/TX timeout or error IRQ, transition through explicit recovery states and bounded retries.

### Firmware file structure recommendation
- `firmware-esp32/components/uwb_driver/`: low-level DW1000/DW3000 driver wrapper.
- `firmware-esp32/components/uwb_ranging/`: ranging/session state machine.
- `firmware-esp32/components/protocol/`: message encode/decode + handlers.
- `firmware-esp32/main/`: bootstrapping, task creation, dependency wiring.

## Concrete protocol contract template

### Message envelope (required fields)
Every message must include:
- `protocolVersion`: integer, starts at `1`.
- `messageType`: string enum (see list below).
- `messageId`: monotonic uint32 per sender session.
- `sessionId`: opaque string/uint identifying active pair session.
- `timestampMs`: monotonic milliseconds from sender boot/session start.
- `payload`: object/binary payload for the specific message type.

### Required message types
- `HELLO`
- `PAIR_REQUEST`
- `PAIR_RESPONSE`
- `SESSION_START`
- `SESSION_ACK`
- `RANGING_START`
- `RANGING_STOP`
- `RANGING_SAMPLE`
- `HEARTBEAT`
- `ERROR`

### Error code baseline
- `1000` `ERR_UNKNOWN_MESSAGE`
- `1001` `ERR_INVALID_SCHEMA`
- `1002` `ERR_UNSUPPORTED_VERSION`
- `1003` `ERR_UNAUTHORIZED`
- `1004` `ERR_SESSION_NOT_FOUND`
- `1005` `ERR_TIMEOUT`
- `1006` `ERR_BUSY`
- `1007` `ERR_RADIO_FAILURE`
- `1008` `ERR_INTERNAL`

### Retry and timeout baseline (initial defaults)
- `PAIR_REQUEST` timeout: `3000 ms`, retries: `3`.
- `SESSION_START` timeout: `2000 ms`, retries: `3`.
- `RANGING_START` timeout: `1500 ms`, retries: `2`.
- `HEARTBEAT` interval: `1000 ms`, disconnect after `3` missed heartbeats.

### Compatibility rules
- Reject lower or unsupported `protocolVersion` with `ERR_UNSUPPORTED_VERSION`.
- Ignore unknown optional fields.
- Never repurpose an existing `messageType` with incompatible payload.
- Additive changes only for minor version updates; breaking changes require version bump.
