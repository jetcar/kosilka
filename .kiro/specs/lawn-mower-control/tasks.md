# Implementation Plan: Lawn Mower Control

## Overview

Implement the lawn-mower-control feature across two codebases: the Android app (Kotlin/Compose/Hilt/Room) and the ESP32 firmware (ESP-IDF/FreeRTOS/DW1000/DW3000). The plan follows Clean Architecture on Android and a modular component model on the ESP32, wiring both sides through the versioned USB JSON protocol extended with `MOVE_TO`, `ZONE_SET`, `COVERAGE_UPDATE`, and `SCHEDULE_SET`.

The emulator is built **first** so development and testing can proceed without any physical hardware. All domain and repository code depends only on the `MowerDevice` interface; neither `UsbMowerDevice` nor `EmulatedMowerDevice` is referenced above the data layer.

## Tasks

- [x] 1. Protocol contract extension
  - [x] 1.1 Update `protocol/message-contract.md` with full payload schemas, field descriptions, and example envelopes for `MOVE_TO`, `ZONE_SET`, `COVERAGE_UPDATE`, and `SCHEDULE_SET`; bump `protocolVersion` to `2`; add new error codes if needed
    - Add canonical envelope examples for all four new message types
    - Document field types, units (mm), and nullability for each payload field
    - _Requirements: 9.5, 9.6_

- [x] 2. Android — core infrastructure
  - [x] 2.1 Create Android project structure: packages `feature/map`, `feature/zone`, `feature/schedule`, `feature/history`, `data/local`, `data/device`, `domain`, `core`; add Hilt application class and module scaffolding; create `prod` and `emulator` build flavors in `build.gradle`
    - Follow the package layout in `copilot-instructions.md`
    - `emulator` flavor source set: `app/src/emulator/…`; `prod` flavor source set: `app/src/prod/…`
    - _Requirements: 1.1–1.8, 10.4_

  - [x] 2.2 Implement `CoroutineDispatchers` provider and `MessageIdGenerator` (thread-safe `AtomicLong`, resets per session)
    - Inject via Hilt; expose `IO`, `Default`, `Main` dispatchers
    - _Requirements: 9.2, 10.4_

  - [ ]* 2.3 Write property test for `MessageIdGenerator` — Property 12: Monotonically Increasing Message IDs
    - **Property 12: Monotonically Increasing Message IDs**
    - **Validates: Requirements 9.2**

  - [x] 2.4 Implement `ProtocolEncoder` and `ProtocolDecoder` (pure Kotlin): serialise/deserialise all message types including `MOVE_TO`, `ZONE_SET`, `COVERAGE_UPDATE`, `SCHEDULE_SET`; validate envelope schema; reject unsupported `protocolVersion`
    - Use sealed `IncomingMessage` hierarchy for decoded messages
    - _Requirements: 1.8, 9.1, 9.4, 9.6_

  - [ ]* 2.5 Write property test for `ProtocolEncoder` — Property 11: Outgoing Envelope Completeness
    - **Property 11: Outgoing Envelope Completeness**
    - **Validates: Requirements 9.1**

  - [ ]* 2.6 Write property test for `ProtocolDecoder` — Property 1: Protocol Version Rejection
    - **Property 1: Protocol Version Rejection**
    - **Validates: Requirements 1.8, 9.6**


- [x] 3. Android — Room database
  - [x] 3.1 Define `AppDatabase` with entities `ZoneEntity`, `CoverageSegmentEntity`, `ScheduleEntity`, `SessionHistoryEntity`, `AnchorEntity`; write DAOs (`ZoneDao`, `CoverageDao`, `ScheduleDao`, `SessionHistoryDao`, `AnchorDao`); add initial migration
    - All schema changes must be migration-backed and versioned
    - _Requirements: 5.4, 6.4, 7.4, 8.1, 8.4_

  - [x] 3.2 Implement `Entity ↔ Domain` mapper functions for all five entities
    - Keep DB entities out of the UI layer
    - _Requirements: 5.4, 6.4, 7.4, 8.1_

  - [ ]* 3.3 Write property test for `ZoneEntity` mapper — Property 7: Zone Persistence Round-Trip
    - **Property 7: Zone Persistence Round-Trip**
    - **Validates: Requirements 5.4**

  - [ ]* 3.4 Write property test for `ScheduleEntity` mapper — Property 16: Schedule Persistence Round-Trip
    - **Property 16: Schedule Persistence Round-Trip**
    - **Validates: Requirements 7.4**

  - [ ]* 3.5 Write property test for `CoverageSegmentEntity` mapper — Property 23: Coverage Persistence Round-Trip
    - **Property 23: Coverage Persistence Round-Trip**
    - **Validates: Requirements 6.4**

  - [ ]* 3.6 Write property test for `SessionHistoryEntity` mapper — Property 17: Session History Record Completeness
    - **Property 17: Session History Record Completeness**
    - **Validates: Requirements 8.1**

- [x] 4. Android — `MowerDevice` interface
  - [x] 4.1 Define the `MowerDevice` interface in `data/device/MowerDevice.kt`: `connect()`, `disconnect()`, `connectionEvents: Flow<ConnectionEvent>`, `send(envelope)`, `incomingMessages: Flow<IncomingMessage>`
    - This is the only type that domain, use-case, and repository code may import from the device layer
    - Define `ConnectionEvent` sealed class (`Connected`, `Disconnected`, `Error`) in the same package
    - _Requirements: 1.1–1.8, 10.1_


- [ ] 5. Android — emulator (build first, no hardware required)
  - [x] 5.1 Implement `EmulatorScenario` sealed class and `EmulatorScenarioEngine` in `core/emulator/`: path model (`List<Point2dMm>`, speed 200 mm/s), scenario state machine, `MutableSharedFlow<IncomingMessage>` message emitter; expose `activateScenario()`, `clearScenario()`, `currentPosition()`
    - Pure Kotlin, no Android dependencies; all coroutines use injected `CoroutineDispatchers`
    - Scenarios: `Normal`, `Drift(driftRateMmPerSec)`, `Stuck(durationMs)`, `SignalInterference(durationMs)`, `SignalLoss(durationMs)`, `Busy(durationMs)`
    - _Requirements: Emulator Architecture_

  - [x]* 5.2 Write property test for `EmulatorScenarioEngine` — Property 24: Emulator Normal Path Progression
    - **Property 24: Emulator Normal Path Progression**
    - **Validates: Emulator Architecture — Normal scenario**

  - [x]* 5.3 Write property test for `EmulatorScenarioEngine` — Property 25: Emulator Drift Accumulation
    - **Property 25: Emulator Drift Accumulation**
    - **Validates: Emulator Architecture — Drift scenario**

  - [x]* 5.4 Write property test for `EmulatorScenarioEngine` — Property 26: Emulator Stuck Position Invariant
    - **Property 26: Emulator Stuck Position Invariant**
    - **Validates: Emulator Architecture — Stuck scenario**

  - [x]* 5.5 Write property test for `EmulatorScenarioEngine` — Property 27: Emulator Signal Interference Quality Bound
    - **Property 27: Emulator Signal Interference Quality Bound**
    - **Validates: Emulator Architecture — Signal Interference scenario**

  - [x]* 5.6 Write property test for `EmulatorScenarioEngine` — Property 28: Emulator Signal Loss Gap
    - **Property 28: Emulator Signal Loss Gap**
    - **Validates: Emulator Architecture — Signal Loss scenario**

  - [x] 5.7 Implement `EmulatedMowerDevice` in `data/device/emulator/EmulatedMowerDevice.kt`: implements `MowerDevice`; delegates to `EmulatorScenarioEngine`; accepts `connect()` immediately; responds to `RANGING_START` by starting the engine's position-update loop; handles `MOVE_TO` (normal ack or `ERR_BUSY` when `Busy` scenario active); emits `COVERAGE_UPDATE` and `HEARTBEAT` on schedule
    - No USB permissions required; runs on any Android emulator or device
    - _Requirements: 1.1–1.8, 2.1–2.6, 4.6_

  - [x]* 5.8 Write property test for `EmulatedMowerDevice` — Property 29: Emulator Busy Response
    - **Property 29: Emulator Busy Response**
    - **Validates: Emulator Architecture — Busy scenario**

  - [x] 5.9 Implement `EmulatorControlViewModel` in `feature/debug/`: expose `StateFlow<EmulatorUiState>` (current scenario, current position); handle `activateScenario` and `clearScenario` intents; only compiled in `emulator` flavor source set
    - _Requirements: Emulator Architecture — Scenario Injection UI_

  - [x] 5.10 Implement `DebugEmulatorScreen` composable in `feature/debug/`: scenario dropdown, numeric parameter inputs (`durationMs`, `driftRateMmPerSec`), Activate/Clear buttons, live position and active-scenario readout; only compiled in `emulator` flavor source set
    - _Requirements: Emulator Architecture — Scenario Injection UI_


- [ ] 6. Android — Hilt `DeviceModule` (both flavors)
  - [x] 6.1 Implement `DeviceModule` for the `emulator` flavor in `app/src/emulator/…/di/DeviceModule.kt`: `@Provides @Singleton` binding for `MowerDevice` → `EmulatedMowerDevice`; `@Provides @Singleton` for `EmulatorScenarioEngine` (with `DefaultEmulatorPath.waypoints`); `@InstallIn(SingletonComponent::class)`
    - _Requirements: Emulator Architecture — DI Wiring_

  - [x] 6.2 Implement `DeviceModule` for the `prod` flavor in `app/src/prod/…/di/DeviceModule.kt`: `@Provides @Singleton` binding for `MowerDevice` → `UsbMowerDevice`; `@InstallIn(SingletonComponent::class)`
    - Depends on `UsbConnectionManager`, `ProtocolEncoder`, `ProtocolDecoder` being available
    - _Requirements: Emulator Architecture — DI Wiring_

  - [x] 6.3 Implement optional runtime toggle (debug builds only): `DataStore<Preferences>` preference key `device_mode` (`REAL` / `EMULATOR`); delegating `MowerDevice` wrapper that reads the preference and forwards calls to the appropriate implementation; surface toggle on the debug settings screen
    - _Requirements: Emulator Architecture — DI Wiring_

- [~] 7. Checkpoint — Ensure emulator builds and all emulator-layer tests pass; verify `DebugEmulatorScreen` is reachable in the `emulator` flavor. Ask the user if questions arise.

- [ ] 8. Android — USB transport layer
  - [~] 8.1 Implement `UsbConnectionManager`: register `BroadcastReceiver` for `ACTION_USB_DEVICE_ATTACHED` / `ACTION_USB_DEVICE_DETACHED`; open `UsbDeviceConnection`; expose `Flow<ConnectionEvent>`; all I/O on `Dispatchers.IO`
    - _Requirements: 1.1, 10.1_

  - [x] 8.2 Implement `UsbMowerDataSource`: write encoded frames to bulk-out endpoint; read from bulk-in endpoint in a dedicated coroutine loop; expose `suspend fun send(envelope)` and `Flow<IncomingMessage>`; used internally by `UsbMowerDevice` only
    - _Requirements: 1.1, 10.4_

  - [x] 8.3 Implement `UsbMowerDevice` in `data/device/usb/UsbMowerDevice.kt`: implements `MowerDevice`; wraps `UsbConnectionManager`, `UsbMowerDataSource`, `ProtocolEncoder`, `ProtocolDecoder`; no repository or use-case code imports this class directly
    - _Requirements: 1.1–1.8, 10.1, 10.4_


- [ ] 9. Android — connection management
  - [x] 9.1 Implement `ConnectMowerUseCase`: inject `MowerDevice`; orchestrate `HELLO → PAIR_REQUEST (retry 3×, 3000 ms) → SESSION_START → SESSION_ACK` handshake; handle `ERR_UNAUTHORIZED` (no retry); expose `ConnectionState` flow
    - _Requirements: 1.1, 1.2, 1.3, 1.7_

  - [x] 9.2 Implement heartbeat loop in `ConnectMowerUseCase`: send `HEARTBEAT` every 1000 ms via `MowerDevice.send()`; terminate session after 3 consecutive missed responses; send `RANGING_STOP` on explicit user disconnect
    - _Requirements: 1.4, 1.5, 1.6_

  - [~] 9.3 Implement `HomeViewModel` and `HomeScreen` composable: show connection status, most recent session summary, connect/disconnect button
    - Expose `StateFlow<HomeUiState>` with `Loading`, `Success`, `Error`, `Empty` variants
    - _Requirements: 1.1–1.7, 8.5_

- [ ] 10. Android — UWB ranging and trilateration
  - [x] 10.1 Implement `TrilaterationSolver.kt`: Gauss-Newton iterative solver over 3+ anchor measurements; return `MowerPosition` or error if fewer than 3 anchors
    - _Requirements: 2.2, 2.7_

  - [ ]* 10.2 Write property test for `TrilaterationSolver` — Property 2: Trilateration Validity
    - **Property 2: Trilateration Validity**
    - **Validates: Requirements 2.2**

  - [ ]* 10.3 Write property test for `TrilaterationSolver` — Property 22: Minimum Anchor Count for Ranging
    - **Property 22: Minimum Anchor Count for Ranging**
    - **Validates: Requirements 2.7**

  - [x] 10.4 Implement `StartRangingUseCase`: inject `MowerDevice`; send `RANGING_START` (sampleRateHz ≥ 5) via `MowerDevice.send()`; filter samples with `quality < 0.5`; apply trilateration; emit `MowerPosition`; detect 3000 ms timeout → `isPositionLost`; send `RANGING_STOP` on screen leave
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_

  - [ ]* 10.5 Write property test for `StartRangingUseCase` sample filter — Property 3: Low-Quality Sample Rejection
    - **Property 3: Low-Quality Sample Rejection**
    - **Validates: Requirements 2.4**

- [ ] 11. Android — 2D map display
  - [x] 11.1 Implement `screenToMapMm` coordinate conversion function and its inverse `mapMmToScreen`
    - _Requirements: 4.1, 4.2_

  - [x]* 11.2 Write property test for coordinate conversion — Property 5: Coordinate Conversion Round-Trip
    - **Property 5: Coordinate Conversion Round-Trip**
    - **Validates: Requirements 4.1, 4.2**

  - [x] 11.3 Implement `MapCanvas` composable: render anchors as labeled markers, mower position marker, zone polygon overlay, coverage filled overlay, destination marker; support pinch-to-zoom and pan via `Modifier.pointerInput`; minimum 0.5 m × 0.5 m cell resolution at default zoom
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

  - [ ]* 11.4 Write property test for map state rendering — Property 4: Map State Rendering Invariants
    - **Property 4: Map State Rendering Invariants**
    - **Validates: Requirements 3.2, 3.5, 3.6**

  - [x] 11.5 Implement `MapViewModel` and `MapScreen` composable: wire `StartRangingUseCase`, expose `MapUiState`, handle tap events for tap-to-move, show "position lost" indicator
    - _Requirements: 2.3, 2.5, 4.3, 4.5_


- [~] 12. Checkpoint — Ensure all tests pass, ask the user if questions arise.

- [ ] 13. Android — tap-to-move navigation
  - [x] 13.1 Implement `MoveMowerUseCase`: inject `MowerDevice`; convert tapped screen coordinate to map mm via `screenToMapMm`; validate coordinate is inside zone (if defined); send `MOVE_TO` via `MowerDevice.send()`; handle `ERR_BUSY` (retain previous marker); handle delivery failure after retries (notify user, revert map)
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 5.7_

  - [x]* 13.2 Write property test for out-of-zone blocking — Property 8: Out-of-Zone MOVE_TO Blocking
    - **Property 8: Out-of-Zone MOVE_TO Blocking**
    - **Validates: Requirements 5.7**

- [ ] 14. Android — zone definition
  - [x] 14.1 Implement `DefineZoneUseCase`: inject `MowerDevice`; collect ≥ 3 vertex taps; send `ZONE_SET` via `MowerDevice.send()` with ordered `vertices` array; persist zone to Room; handle firmware ERROR (restore previous zone); overwrite persisted zone on edit
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6_

  - [x]* 14.2 Write property test for `DefineZoneUseCase` — Property 6: ZONE_SET Message Schema and Vertex Ordering
    - **Property 6: ZONE_SET Message Schema and Vertex Ordering**
    - **Validates: Requirements 5.2, 5.3**

  - [x] 14.3 Implement `ZoneViewModel` and `ZoneScreen` composable: zone-drawing mode, vertex placement, confirm/cancel, display current zone
    - _Requirements: 5.1, 5.4, 5.5, 5.6_

- [ ] 15. Android — coverage tracking
  - [x] 15.1 Implement `TrackCoverageUseCase`: receive `COVERAGE_UPDATE` messages from `MowerDevice.incomingMessages`; accumulate segments (union); update map overlay within 500 ms; persist segments to Room; clear on new session start
    - _Requirements: 6.1, 6.3, 6.4, 6.5_

  - [x]* 15.2 Write property test for coverage accumulation — Property 9: Coverage Accumulation Invariant
    - **Property 9: Coverage Accumulation Invariant**
    - **Validates: Requirements 6.3**

  - [x] 15.3 Implement `computeCoveragePercent`: rasterise zone polygon and coverage segments onto 50 mm grid; compute ratio; display percentage on map; update on each `COVERAGE_UPDATE`
    - _Requirements: 6.6_

  - [x]* 15.4 Write property test for coverage percentage — Property 10: Coverage Percentage Correctness
    - **Property 10: Coverage Percentage Correctness**
    - **Validates: Requirements 6.6**

- [ ] 16. Android — scheduling
  - [x] 16.1 Implement `ManageScheduleUseCase`: inject `MowerDevice`; create/delete schedules; send `SCHEDULE_SET` via `MowerDevice.send()` (with `deleted: true` for deletions); persist to Room; queue with `pendingSync = true` when offline
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6_

  - [x]* 16.2 Write property test for `SCHEDULE_SET` message schema — Property 15: SCHEDULE_SET Message Schema
    - **Property 15: SCHEDULE_SET Message Schema**
    - **Validates: Requirements 7.3**

  - [x] 16.3 Implement `PendingSyncUseCase`: observe `ConnectionState`; on transition to `Connected`, query schedules with `pendingSync = true` and send `SCHEDULE_SET` for each via `MowerDevice.send()`; clear flag on success
    - _Requirements: 7.6_

  - [x] 16.4 Implement `ScheduleViewModel` and `ScheduleScreen` composable: list active schedules (start time, days, zone name), create/delete schedule UI
    - _Requirements: 7.1, 7.4, 7.7_


- [x] 17. Android — session history
  - [x] 17.1 Implement `SessionHistoryRepository`: persist `SessionRecord` on session end (start/end timestamps, duration, distance, coverage%); load records sorted by `startTimestampUtcMs` DESC; schedule WorkManager daily cleanup for records older than 90 days
    - _Requirements: 8.1, 8.2, 8.4_

  - [x]* 17.2 Write property test for session history sort order — Property 18: Session History Sort Order
    - **Property 18: Session History Sort Order**
    - **Validates: Requirements 8.2**

  - [x]* 17.3 Write property test for 90-day retention — Property 19: Session History 90-Day Retention
    - **Property 19: Session History 90-Day Retention**
    - **Validates: Requirements 8.4**

  - [x] 17.4 Implement `LoadHistoryUseCase`, `HistoryViewModel`, and `HistoryScreen` composable: list records, tap to view coverage map for that session
    - _Requirements: 8.2, 8.3_

- [x] 18. Android — error handling and offline resilience
  - [x] 18.1 Implement structured error logging in `ProtocolDecoder`: log `code`, `name`, `failedMessageId` with structured tags; route ERROR messages to the appropriate use case; display human-readable snackbar notifications via `UiEvent`
    - _Requirements: 10.2_

  - [x]* 18.2 Write property test for error logging — Property 20: Error Message Logging Completeness
    - **Property 20: Error Message Logging Completeness**
    - **Validates: Requirements 10.2**

  - [x] 18.3 Implement offline data availability: ensure all ViewModels load persisted zone, coverage, history, schedules, and anchor data from Room when `ConnectionState` is `Disconnected`; verify no main-thread I/O with `StrictMode` in debug builds
    - _Requirements: 10.4, 10.5_

  - [x]* 18.4 Write property test for offline data availability — Property 21: Offline Data Availability
    - **Property 21: Offline Data Availability**
    - **Validates: Requirements 10.5**

- [~] 19. Checkpoint — Ensure all Android tests pass, ask the user if questions arise.

- [ ] 20. ESP32 firmware — project structure and USB transport
  - [x] 20.1 Create ESP-IDF component directories: `components/uwb_driver/`, `components/uwb_ranging/`, `components/protocol/`, `components/usb_transport/`; add `CMakeLists.txt` for each; add `dw_config.h` with antenna delay, channel, preamble, STS params
    - _Requirements: 9.1–9.6_

  - [x] 20.2 Implement `usb_serial.c` and `frame_codec.c`: read/write length-prefixed JSON frames (4-byte LE length prefix + JSON body) over USB CDC-ACM; validate frame length before parsing
    - _Requirements: 1.1, 9.1_

- [ ] 21. ESP32 firmware — protocol encode/decode
  - [~] 21.1 Implement `msg_decode.c` and `msg_encode.c` using `cJSON`: parse and serialise all message types including `MOVE_TO`, `ZONE_SET`, `COVERAGE_UPDATE`, `SCHEDULE_SET`; validate all required envelope fields; return `ESP_ERR_INVALID_ARG` on schema errors; never silently ignore parse errors
    - _Requirements: 9.1, 9.4_

  - [ ]* 21.2 Write host-side unit tests for `msg_decode` / `msg_encode` round-trip — Property 14: Firmware Rejects Malformed Envelopes
    - **Property 14: Firmware Rejects Malformed Envelopes**
    - **Validates: Requirements 9.4**

- [ ] 22. ESP32 firmware — session state machine
  - [~] 22.1 Implement `session_sm.c`: states `IDLE → HELLO_SENT → PAIRING → AUTHENTICATED → RANGING → ERROR`; reject `MOVE_TO`, `ZONE_SET`, `SCHEDULE_SET` outside `AUTHENTICATED`/`RANGING` with `ERR_UNAUTHORIZED`; handle heartbeat timeout (3 misses → disconnect)
    - _Requirements: 1.3, 1.4, 1.5, 9.3_

  - [ ]* 22.2 Write host-side unit tests for `session_sm` — Property 13: Firmware Rejects Unauthenticated Control Messages
    - **Property 13: Firmware Rejects Unauthenticated Control Messages**
    - **Validates: Requirements 9.3**

  - [~] 22.3 Implement `msg_handlers.c`: dispatch decoded messages to session SM and ranging SM; send `RANGING_SAMPLE` envelopes via FreeRTOS queue to protocol task
    - _Requirements: 1.1–1.8, 2.1_

- [ ] 23. ESP32 firmware — UWB driver and ranging
  - [~] 23.1 Implement `dw_driver.c` and `dw_spi.c`: thin wrapper over DW1000/DW3000 SPI; handle RX/TX timeout IRQs with explicit recovery states and bounded retries; keep ISRs minimal (capture signal only, defer to tasks)
    - _Requirements: 2.1, 2.2_

  - [~] 23.2 Implement `ranging_sm.c`: states `STOPPED → STARTING → ACTIVE → STOPPING`; drive TWR exchanges with each configured anchor; publish `RANGING_SAMPLE` envelopes to protocol task queue
    - _Requirements: 2.1, 2.2_

  - [~] 23.3 Implement `trilateration.c`: `esp_err_t trilaterate(anchors, count, distances_mm, out_pos)` using least-squares minimisation; return `ESP_ERR_INVALID_ARG` if fewer than 3 anchors
    - _Requirements: 2.2, 2.7_

  - [ ]* 23.4 Write host-side unit tests for `trilateration.c` with known test vectors
    - Test vectors shared with Android `TrilaterationSolver` via `protocol/message-contract.md`
    - _Requirements: 2.2_

- [ ] 24. ESP32 firmware — main bootstrapping
  - [~] 24.1 Implement `main.c`: create FreeRTOS tasks (`uwb_task`, `protocol_task`, `health_task`) with explicit priorities; wire `usb_transport`, `protocol`, `uwb_ranging`, and `uwb_driver` components together; feed watchdog from each task
    - _Requirements: 1.1, 2.1_

- [~] 25. Checkpoint — Ensure all firmware tests pass, ask the user if questions arise.


- [x] 26. Integration wiring and end-to-end validation
  - [x] 26.1 Wire all Android use cases (`ConnectMowerUseCase`, `StartRangingUseCase`, `MoveMowerUseCase`, `DefineZoneUseCase`, `TrackCoverageUseCase`, `ManageScheduleUseCase`) to `MowerDevice` via Hilt injection; verify `ConnectionState` propagates to all ViewModels; confirm no use case or repository imports `UsbMowerDevice` or `EmulatedMowerDevice` directly
    - _Requirements: 1.1–1.8, 2.1–2.7, 4.1–4.6, 5.1–5.7, 6.1–6.6, 7.1–7.7_

  - [x] 26.2 Wire `PendingSyncUseCase` into the connection lifecycle so pending schedules are flushed on reconnect
    - _Requirements: 7.6_

  - [x] 26.3 Wire `SessionHistoryRepository` to session lifecycle: persist `SessionRecord` on session end; display most recent session summary on `HomeScreen`
    - _Requirements: 8.1, 8.5_

  - [x]* 26.4 Write integration tests for the Android connection state machine: test each state transition with a mock `MowerDevice` (not `UsbMowerDevice`); test heartbeat timeout with fake clock
    - _Requirements: 1.1–1.8_

  - [x]* 26.5 Write integration tests for offline schedule queuing: verify pending schedules are sent on reconnection using mock `MowerDevice`
    - _Requirements: 7.6_

  - [x]* 26.6 Write integration tests for coverage overlay clearing: verify overlay resets when a new session starts
    - _Requirements: 6.5_

- [~] 27. Final checkpoint — Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP
- The emulator (Tasks 5–6) is built before the USB transport (Task 8) so all development and testing can proceed without hardware
- All domain, use-case, and repository code depends only on `MowerDevice`; `UsbMowerDevice` and `EmulatedMowerDevice` are never imported above the data layer
- Hilt provides the concrete `MowerDevice` binding per build flavor: `emulator` → `EmulatedMowerDevice`, `prod` → `UsbMowerDevice`
- `DebugEmulatorScreen` and `EmulatorControlViewModel` are compiled only in the `emulator` flavor source set
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation at logical boundaries
- Property tests use Kotest property testing (Kotlin) and Unity/CMock or a custom host-side harness (C) with minimum 100 iterations per property
- Property tests are tagged: `Feature: lawn-mower-control, Property N: <property_text>`
- Unit tests validate specific examples and edge cases; property tests validate universal invariants
- The ESP32 host-side tests run on the development machine (not on hardware) for fast feedback; hardware-in-the-loop smoke tests (pair, ranging start/stop, reconnect) are separate


## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["2.1", "3.1", "20.1"] },
    { "id": 2, "tasks": ["2.2", "3.2", "4.1", "20.2"] },
    { "id": 3, "tasks": ["2.3", "2.4", "3.3", "3.4", "3.5", "3.6", "21.1"] },
    { "id": 4, "tasks": ["2.5", "2.6", "21.2", "22.1"] },
    { "id": 5, "tasks": ["5.1", "22.2", "22.3", "23.1"] },
    { "id": 6, "tasks": ["5.2", "5.3", "5.4", "5.5", "5.6", "23.2", "23.3"] },
    { "id": 7, "tasks": ["5.7", "23.4", "24.1"] },
    { "id": 8, "tasks": ["5.8", "5.9", "6.1"] },
    { "id": 9, "tasks": ["5.10", "6.2", "6.3"] },
    { "id": 10, "tasks": ["8.1"] },
    { "id": 11, "tasks": ["8.2"] },
    { "id": 12, "tasks": ["8.3"] },
    { "id": 13, "tasks": ["9.1", "10.1", "11.1"] },
    { "id": 14, "tasks": ["9.2", "10.2", "10.3", "11.2", "11.3"] },
    { "id": 15, "tasks": ["9.3", "10.4", "10.5", "11.4", "11.5", "13.1", "14.1", "15.1", "16.1", "17.1"] },
    { "id": 16, "tasks": ["13.2", "14.2", "14.3", "15.2", "15.3", "16.2", "16.3", "17.2", "17.3", "17.4", "18.1"] },
    { "id": 17, "tasks": ["15.4", "16.4", "18.2", "18.3"] },
    { "id": 18, "tasks": ["18.4", "26.1"] },
    { "id": 19, "tasks": ["26.2", "26.3"] },
    { "id": 20, "tasks": ["26.4", "26.5", "26.6"] }
  ]
}
```
