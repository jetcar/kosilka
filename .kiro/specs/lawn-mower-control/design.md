# Design Document — Lawn Mower Control

## Overview

The lawn-mower-control feature is an Android application that connects to an ESP32-based autonomous lawn mower over USB Type-C, displays a live 2D map of the lawn, and lets the user command the mower through tap-to-move navigation, zone definition, coverage tracking, scheduling, and session history. The mower's position is computed in real time from UWB multi-anchor ranging (3+ DW1000/DW3000 anchors). The Android app and ESP32 firmware communicate using the versioned JSON envelope protocol defined in `protocol/message-contract.md`, extended with four new message types: `MOVE_TO`, `ZONE_SET`, `COVERAGE_UPDATE`, and `SCHEDULE_SET`.

The design follows Clean Architecture on Android (UI → ViewModel → UseCase → Repository → DataSource) and a modular component model on the ESP32 (driver → ranging → protocol → application tasks).

---

## Architecture

### Android — Layer Diagram

```
┌─────────────────────────────────────────────────────────────┐
│  UI Layer (Jetpack Compose)                                  │
│  MapScreen · HomeScreen · ZoneScreen · ScheduleScreen        │
│  HistoryScreen · DebugEmulatorScreen (debug builds only)     │
└────────────────────────┬────────────────────────────────────┘
                         │ UiState / UiEvent
┌────────────────────────▼────────────────────────────────────┐
│  ViewModel Layer (Hilt-injected)                             │
│  MapViewModel · HomeViewModel · ZoneViewModel                │
│  ScheduleViewModel · HistoryViewModel                        │
│  EmulatorControlViewModel (debug builds only)                │
└────────────────────────┬────────────────────────────────────┘
                         │ suspend / Flow
┌────────────────────────▼────────────────────────────────────┐
│  Domain Layer (pure Kotlin)                                  │
│  Use Cases: ConnectMowerUseCase · StartRangingUseCase        │
│  MoveMowerUseCase · DefineZoneUseCase · TrackCoverageUseCase │
│  ManageScheduleUseCase · LoadHistoryUseCase                  │
│  Entities: MowerPosition · Zone · CoverageSegment            │
│  Schedule · SessionRecord · Anchor                           │
└────────────────────────┬────────────────────────────────────┘
                         │ Repository interfaces
┌────────────────────────▼────────────────────────────────────┐
│  Data Layer                                                  │
│  MowerRepository · ZoneRepository · CoverageRepository       │
│  ScheduleRepository · SessionHistoryRepository               │
│  ├── Local: Room DAOs (ZoneDao, CoverageDao, ScheduleDao,    │
│  │          SessionHistoryDao)                               │
│  └── Device: MowerDevice (interface)                         │
│       ├── UsbMowerDevice  (production)                       │
│       └── EmulatedMowerDevice  (emulator / debug)            │
└────────────────────────┬────────────────────────────────────┘
                         │ USB Host API  ─or─  in-process coroutines
┌────────────────────────▼────────────────────────────────────┐
│  Platform / Core                                             │
│  UsbConnectionManager · ProtocolEncoder · ProtocolDecoder    │
│  MessageIdGenerator · CoroutineDispatchers                   │
│  EmulatorScenarioEngine (emulator builds only)               │
└─────────────────────────────────────────────────────────────┘
```

### ESP32 — Component Diagram

```
┌──────────────────────────────────────────────────────────────┐
│  main.c — bootstrapping, task creation, dependency wiring    │
└──────────┬───────────────────────────────────────────────────┘
           │
┌──────────▼──────────┐  ┌──────────────────────┐  ┌──────────────────────┐
│  protocol/          │  │  uwb_ranging/         │  │  uwb_driver/         │
│  msg_encode.c       │  │  ranging_sm.c         │  │  dw_driver.c         │
│  msg_decode.c       │  │  trilateration.c      │  │  dw_spi.c            │
│  msg_handlers.c     │  │  anchor_config.c      │  │  dw_irq.c            │
│  session_sm.c       │  └──────────────────────┘  └──────────────────────┘
└─────────────────────┘
           │
┌──────────▼──────────┐
│  usb_transport/     │
│  usb_serial.c       │
│  frame_codec.c      │
└─────────────────────┘
```

---

## Components and Interfaces

### Android Components

#### MowerDevice (Interface)

The central abstraction that decouples all domain and repository code from the physical transport. Defined in the data layer as a Kotlin interface:

```kotlin
interface MowerDevice {
    /** Lifecycle */
    suspend fun connect(): Result<Unit>
    suspend fun disconnect()
    val connectionEvents: Flow<ConnectionEvent>

    /** Outgoing commands */
    suspend fun send(envelope: Envelope): Result<Unit>

    /** Incoming messages */
    val incomingMessages: Flow<IncomingMessage>
}
```

All use cases and repositories depend only on `MowerDevice`. Neither the domain layer nor the ViewModel layer imports any USB or emulator type. Hilt provides the concrete binding at the application graph boundary.

---

#### UsbMowerDevice
Implements `MowerDevice` using the existing `UsbConnectionManager` + `UsbMowerDataSource` + `ProtocolEncoder`/`ProtocolDecoder` stack. This is the production binding, provided by Hilt in the `prod` flavor or when the runtime toggle is set to `REAL`.

#### UsbConnectionManager
Owns the USB Host API lifecycle. Registers a `BroadcastReceiver` for `ACTION_USB_DEVICE_ATTACHED` / `ACTION_USB_DEVICE_DETACHED`. Opens the `UsbDeviceConnection`, claims the interface, and exposes a `Flow<ConnectionEvent>` to the data layer. All I/O runs on `Dispatchers.IO`.

#### ProtocolEncoder / ProtocolDecoder
Pure Kotlin objects. `ProtocolEncoder` serialises domain commands into JSON envelopes. `ProtocolDecoder` deserialises raw bytes into typed `IncomingMessage` sealed classes. Both validate the envelope schema and reject messages with missing fields or unsupported `protocolVersion`.

#### MessageIdGenerator
Thread-safe monotonic counter (`AtomicLong`) scoped to a single session. Resets on session teardown.

#### UsbMowerDataSource
Bridges `UsbConnectionManager` and the protocol layer. Writes encoded frames to the USB bulk-out endpoint and reads from the bulk-in endpoint in a dedicated coroutine loop. Exposes `suspend fun send(envelope: Envelope)` and `Flow<IncomingMessage>`. Used internally by `UsbMowerDevice`; not referenced directly by repositories or use cases.

#### EmulatedMowerDevice
Implements `MowerDevice` entirely in-process — no USB hardware required. Provided by Hilt in the `emulator` build flavor or when the runtime toggle is set to `EMULATOR`. Internally delegates to `EmulatorScenarioEngine` to produce a realistic stream of `IncomingMessage` events and to handle outgoing commands.

Key behaviors:
- Accepts `connect()` immediately and emits `ConnectionEvent.Connected`.
- Responds to `RANGING_START` by starting the scenario engine's position-update loop.
- Responds to `MOVE_TO` commands: acknowledges normally, or returns `ERR_BUSY` (1006) if the `Busy` scenario is active.
- Emits `COVERAGE_UPDATE` messages as the simulated mower traverses its path.
- Emits `HEARTBEAT` responses on schedule.
- All scenario state is controlled via `EmulatorScenarioEngine`.

#### Repository Implementations
Each repository implementation combines a Room DAO (local persistence) and `MowerDevice` (device communication). Repositories are the single source of truth for their domain entity. They never import `UsbMowerDevice` or `EmulatedMowerDevice` directly — the `MowerDevice` interface is injected by Hilt.

#### Room Database
Single `AppDatabase` with entities: `ZoneEntity`, `CoverageSegmentEntity`, `ScheduleEntity`, `SessionHistoryEntity`, `AnchorEntity`. All schema changes are migration-backed.

#### ViewModels
Each screen has a dedicated ViewModel that:
- Exposes `StateFlow<UiState>` (sealed class with `Loading`, `Success`, `Error`, `Empty` variants).
- Exposes `SharedFlow<UiEvent>` for one-shot events (snackbars, navigation).
- Delegates business logic to use cases.
- Launches coroutines with `viewModelScope`.

#### Composable Screens
Stateless composables that receive state and emit callbacks. The `MapCanvas` composable uses `Canvas` with `drawPath`, `drawCircle`, and `drawLine` primitives. Gesture handling uses `Modifier.pointerInput` for pinch-to-zoom and pan, and `Modifier.clickable` for tap-to-move.

---

### ESP32 Components

#### session_sm (Session State Machine)
States: `IDLE → HELLO_SENT → PAIRING → AUTHENTICATED → RANGING → ERROR`. Transitions are driven by incoming message types. Rejects control messages (`MOVE_TO`, `ZONE_SET`, `SCHEDULE_SET`) in any state other than `AUTHENTICATED` or `RANGING`.

#### msg_decode / msg_encode
Parses and serialises JSON envelopes using `cJSON`. Validates all required envelope fields and payload schemas. Returns explicit `esp_err_t` codes; never silently ignores parse errors.

#### ranging_sm (Ranging State Machine)
States: `STOPPED → STARTING → ACTIVE → STOPPING`. Drives the DW driver to schedule TWR (Two-Way Ranging) exchanges with each configured anchor. Publishes `RANGING_SAMPLE` envelopes to the protocol task via a FreeRTOS queue.

#### trilateration
Pure C function: `esp_err_t trilaterate(const anchor_t *anchors, size_t count, const float *distances_mm, point2d_t *out_pos)`. Uses least-squares minimisation over 3+ anchor measurements. Returns `ESP_ERR_INVALID_ARG` if fewer than 3 anchors are provided.

#### dw_driver
Thin wrapper over the DW1000/DW3000 SPI driver. Isolates antenna delay, channel, preamble, and STS parameters in `dw_config.h`. Handles RX/TX timeout IRQs with explicit recovery states and bounded retries.

#### usb_serial / frame_codec
Reads and writes length-prefixed JSON frames over the USB CDC-ACM interface. `frame_codec` handles framing (4-byte little-endian length prefix + JSON body). Validates frame length before parsing.

---

## Emulator Architecture

### Purpose

`EmulatedMowerDevice` lets the full Android app run and be tested without any physical ESP32 or UWB hardware. It is the default binding in the `emulator` build flavor and can also be toggled at runtime from a debug settings screen.

### EmulatorScenarioEngine

`EmulatorScenarioEngine` is a pure-Kotlin coroutine-based engine that drives the simulated mower. It owns:

- A **path model**: an ordered list of `Point2dMm` waypoints the mower follows at a configurable speed (default 200 mm/s).
- A **scenario state machine**: tracks which scenario (if any) is currently active and for how long.
- A **message emitter**: produces `IncomingMessage` objects on a `MutableSharedFlow` consumed by `EmulatedMowerDevice`.

```kotlin
class EmulatorScenarioEngine(
    private val path: List<Point2dMm>,
    private val speedMmPerSec: Float = 200f,
    private val dispatchers: CoroutineDispatchers
) {
    val messages: SharedFlow<IncomingMessage>

    fun activateScenario(scenario: EmulatorScenario)
    fun clearScenario()
    fun currentPosition(): Point2dMm
}

sealed class EmulatorScenario {
    object Normal : EmulatorScenario()
    data class Drift(val driftRateMmPerSec: Float) : EmulatorScenario()
    data class Stuck(val durationMs: Long) : EmulatorScenario()
    data class SignalInterference(val durationMs: Long) : EmulatorScenario()
    data class SignalLoss(val durationMs: Long) : EmulatorScenario()
    data class Busy(val durationMs: Long) : EmulatorScenario()
}
```

### Simulated Scenarios

| Scenario | Observable effect on the App |
|---|---|
| **Normal** | `RANGING_SAMPLE` messages at `sampleRateHz`, `quality` ≥ 0.9, position advances along path |
| **Drift** | `RANGING_SAMPLE` messages emitted normally, but reported position drifts away from true path at `driftRateMmPerSec`; offset accumulates monotonically |
| **Stuck** | `RANGING_SAMPLE` messages continue at normal rate but position is frozen at the stuck point for `durationMs`; resumes normal movement after |
| **Signal Interference** | `RANGING_SAMPLE` messages emitted with `quality` < 0.5 for `durationMs`; App discards samples → position appears frozen |
| **Signal Loss** | No `RANGING_SAMPLE` messages emitted for `durationMs` (> 3000 ms by default); App triggers `isPositionLost = true` |
| **Busy** | Any `MOVE_TO` command received during `durationMs` is answered with `ERROR` code `1006` (`ERR_BUSY`) |

### Scenario Injection UI

In debug/emulator builds, a `DebugEmulatorScreen` (accessible from the app's debug settings) exposes:

- A dropdown to select the active scenario.
- Numeric inputs for scenario parameters (`durationMs`, `driftRateMmPerSec`).
- An "Activate" button that calls `EmulatorScenarioEngine.activateScenario(...)`.
- A "Clear" button that calls `EmulatorScenarioEngine.clearScenario()`.
- A live readout of the current simulated position and active scenario.

`EmulatorControlViewModel` mediates between `DebugEmulatorScreen` and `EmulatorScenarioEngine`. It is only compiled in debug/emulator builds (guarded by a Hilt module with `@InstallIn(SingletonComponent::class)` and a build-flavor-specific source set).

### DI Wiring

Two Hilt modules provide the `MowerDevice` binding:

```kotlin
// prod flavor: app/src/prod/…/DeviceModule.kt
@Module
@InstallIn(SingletonComponent::class)
object DeviceModule {
    @Provides @Singleton
    fun provideMowerDevice(
        usbConnectionManager: UsbConnectionManager,
        encoder: ProtocolEncoder,
        decoder: ProtocolDecoder
    ): MowerDevice = UsbMowerDevice(usbConnectionManager, encoder, decoder)
}

// emulator flavor: app/src/emulator/…/DeviceModule.kt
@Module
@InstallIn(SingletonComponent::class)
object DeviceModule {
    @Provides @Singleton
    fun provideMowerDevice(
        engine: EmulatorScenarioEngine
    ): MowerDevice = EmulatedMowerDevice(engine)

    @Provides @Singleton
    fun provideEmulatorScenarioEngine(
        dispatchers: CoroutineDispatchers
    ): EmulatorScenarioEngine = EmulatorScenarioEngine(
        path = DefaultEmulatorPath.waypoints,
        dispatchers = dispatchers
    )
}
```

A runtime toggle (stored in `DataStore<Preferences>` and surfaced on the debug settings screen) can also switch between `UsbMowerDevice` and `EmulatedMowerDevice` without a rebuild, using a delegating `MowerDevice` wrapper that reads the preference and forwards calls. This is only available in debug builds.

### Build Flavors

```
productFlavors {
    prod   { /* UsbMowerDevice bound */ }
    emulator { /* EmulatedMowerDevice bound; DebugEmulatorScreen included */ }
}
```

The `emulator` flavor depends on no additional Android permissions (no `USB_PERMISSION` required). It can run on any Android emulator or device.

---

## Data Models

### Protocol Extension — New Message Types

#### MOVE_TO (App → Firmware)
```json
{
  "protocolVersion": 2,
  "messageType": "MOVE_TO",
  "messageId": 201,
  "sessionId": "a1b2c3d4",
  "timestampMs": 5000,
  "payload": {
    "targetXMm": 3200,
    "targetYMm": 1500
  }
}
```

#### ZONE_SET (App → Firmware)
```json
{
  "protocolVersion": 2,
  "messageType": "ZONE_SET",
  "messageId": 202,
  "sessionId": "a1b2c3d4",
  "timestampMs": 5100,
  "payload": {
    "zoneId": "zone-001",
    "vertices": [
      { "xMm": 0,    "yMm": 0    },
      { "xMm": 5000, "yMm": 0    },
      { "xMm": 5000, "yMm": 4000 },
      { "xMm": 0,    "yMm": 4000 }
    ]
  }
}
```

#### COVERAGE_UPDATE (Firmware → App)
```json
{
  "protocolVersion": 2,
  "messageType": "COVERAGE_UPDATE",
  "messageId": 88,
  "sessionId": "a1b2c3d4",
  "timestampMs": 8000,
  "payload": {
    "segments": [
      { "fromXMm": 100, "fromYMm": 200, "toXMm": 400, "toYMm": 200 },
      { "fromXMm": 400, "fromYMm": 200, "toXMm": 400, "toYMm": 500 }
    ]
  }
}
```

#### SCHEDULE_SET (App → Firmware)
```json
{
  "protocolVersion": 2,
  "messageType": "SCHEDULE_SET",
  "messageId": 203,
  "sessionId": "a1b2c3d4",
  "timestampMs": 5200,
  "payload": {
    "scheduleId": "sched-001",
    "startTimeUtcHhmm": "07:30",
    "daysOfWeek": [1, 3, 5],
    "zoneId": "zone-001",
    "deleted": false
  }
}
```

### Android Domain Entities

```kotlin
data class Anchor(val id: String, val xMm: Int, val yMm: Int, val label: String)

data class MowerPosition(val xMm: Int, val yMm: Int, val timestampMs: Long)

data class Zone(
    val id: String,
    val vertices: List<Point2dMm>
)

data class Point2dMm(val xMm: Int, val yMm: Int)

data class CoverageSegment(
    val fromXMm: Int, val fromYMm: Int,
    val toXMm: Int,   val toYMm: Int
)

data class Schedule(
    val scheduleId: String,
    val startTimeUtcHhmm: String,   // "HH:MM"
    val daysOfWeek: List<Int>,      // 0=Sunday … 6=Saturday
    val zoneId: String?,
    val isDeleted: Boolean = false,
    val pendingSync: Boolean = false
)

data class SessionRecord(
    val sessionId: String,
    val startTimestampUtcMs: Long,
    val endTimestampUtcMs: Long,
    val durationSeconds: Long,
    val totalDistanceMm: Long,
    val coveragePercent: Float,
    val coverageSegments: List<CoverageSegment>
)
```

### Room Entities

```kotlin
@Entity(tableName = "zones")
data class ZoneEntity(
    @PrimaryKey val id: String,
    val verticesJson: String   // JSON array of {xMm, yMm}
)

@Entity(tableName = "coverage_segments")
data class CoverageSegmentEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val sessionId: String,
    val fromXMm: Int, val fromYMm: Int,
    val toXMm: Int,   val toYMm: Int
)

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey val scheduleId: String,
    val startTimeUtcHhmm: String,
    val daysOfWeekJson: String,  // JSON array of ints
    val zoneId: String?,
    val pendingSync: Boolean
)

@Entity(tableName = "session_history")
data class SessionHistoryEntity(
    @PrimaryKey val sessionId: String,
    val startTimestampUtcMs: Long,
    val endTimestampUtcMs: Long,
    val durationSeconds: Long,
    val totalDistanceMm: Long,
    val coveragePercent: Float
)

@Entity(tableName = "anchors")
data class AnchorEntity(
    @PrimaryKey val id: String,
    val xMm: Int,
    val yMm: Int,
    val label: String
)
```

### UI State Models

```kotlin
// MapScreen
sealed class MapUiState {
    object Loading : MapUiState()
    data class Success(
        val anchors: List<Anchor>,
        val mowerPosition: MowerPosition?,
        val zone: Zone?,
        val coverageSegments: List<CoverageSegment>,
        val coveragePercent: Float,
        val destinationMarker: Point2dMm?,
        val isPositionLost: Boolean,
        val isConnected: Boolean,
        val isRangingActive: Boolean
    ) : MapUiState()
    data class Error(val message: String) : MapUiState()
}

// ConnectionState (shared across ViewModels via a singleton)
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val sessionId: String) : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()
}
```

---

## Key Flows

### USB Connection Handshake

```
App                                    Firmware
 │                                        │
 │── HELLO ──────────────────────────────►│
 │── PAIR_REQUEST ────────────────────────►│
 │◄── PAIR_RESPONSE (accepted: true) ─────│
 │── SESSION_START ───────────────────────►│
 │◄── SESSION_ACK (ok: true) ─────────────│
 │                                        │
 │  [Session active — HEARTBEAT every 1s] │
```

Retry logic for `PAIR_REQUEST`: up to 3 attempts with 3000 ms timeout each, implemented in `ConnectMowerUseCase` using `retry(3)` on a coroutine flow.

### Trilateration Algorithm

Given anchors A₁…Aₙ (n ≥ 3) at known positions (xᵢ, yᵢ) and measured distances dᵢ (mm), the mower position (x, y) is found by minimising:

```
f(x, y) = Σᵢ [ sqrt((x - xᵢ)² + (y - yᵢ)²) - dᵢ ]²
```

The Android implementation uses a Gauss-Newton iterative solver in `TrilaterationSolver.kt`. The ESP32 implementation uses the same algorithm in `trilateration.c`. Both implementations share the same test vectors defined in `protocol/message-contract.md`.

### Coordinate Conversion (Screen → Map)

```kotlin
fun screenToMapMm(
    screenPoint: Offset,
    canvasSize: Size,
    mapBoundsXMm: ClosedRange<Int>,
    mapBoundsYMm: ClosedRange<Int>,
    zoomScale: Float,
    panOffset: Offset
): Point2dMm {
    val adjustedX = (screenPoint.x - panOffset.x) / zoomScale
    val adjustedY = (screenPoint.y - panOffset.y) / zoomScale
    val xMm = (adjustedX / canvasSize.width * (mapBoundsXMm.endInclusive - mapBoundsXMm.start) + mapBoundsXMm.start).roundToInt()
    val yMm = (adjustedY / canvasSize.height * (mapBoundsYMm.endInclusive - mapBoundsYMm.start) + mapBoundsYMm.start).roundToInt()
    return Point2dMm(xMm, yMm)
}
```

### Coverage Percentage Calculation

Coverage percentage is computed by rasterising both the zone polygon and the accumulated coverage segments onto a grid with 50 mm cell resolution, then dividing the count of covered cells by the count of cells inside the zone polygon.

```kotlin
fun computeCoveragePercent(zone: Zone, segments: List<CoverageSegment>, cellSizeMm: Int = 50): Float {
    val zoneCells = rasterizePolygon(zone.vertices, cellSizeMm)
    val coveredCells = rasterizeSegments(segments, cellSizeMm)
    val intersection = coveredCells.intersect(zoneCells)
    return if (zoneCells.isEmpty()) 0f else intersection.size.toFloat() / zoneCells.size
}
```

### Offline Schedule Queuing

When `ScheduleRepository.saveSchedule()` is called while disconnected, the schedule is persisted with `pendingSync = true`. A `PendingSyncUseCase` observes `ConnectionState` and, on each transition to `Connected`, queries all schedules with `pendingSync = true` and sends `SCHEDULE_SET` messages for each, then clears the flag.

---

## Error Handling

| Scenario | Detection | Response |
|---|---|---|
| USB disconnect during session | `UsbConnectionManager` detects `ACTION_USB_DEVICE_DETACHED` or 3 missed heartbeats within 3000 ms | Persist coverage, show reconnection prompt, transition to `Disconnected` state |
| `PAIR_RESPONSE` not received | 3000 ms timeout × 3 retries | Show "connection failed" notification |
| `ERR_UNAUTHORIZED` (1003) during pairing | `ProtocolDecoder` routes ERROR to `ConnectMowerUseCase` | Show "authentication failure", no retry |
| `ERR_UNSUPPORTED_VERSION` (1002) | `ProtocolDecoder` checks `protocolVersion` on every envelope | Reject envelope, send ERROR 1002, show version mismatch notification |
| `ERR_BUSY` (1006) on `MOVE_TO` | `MoveMowerUseCase` receives ERROR response | Show "mower busy" snackbar, retain previous destination marker |
| `MOVE_TO` / `ZONE_SET` delivery failure after retries | `UsbMowerDataSource` exhausts retry count | Notify user, revert map to last confirmed state |
| Low-quality ranging sample (quality < 0.5) | `StartRangingUseCase` filters samples before trilateration | Discard sample silently |
| No ranging sample for 3000 ms | `StartRangingUseCase` uses `timeout(3000)` on sample flow | Set `isPositionLost = true` in `MapUiState` |
| `ZONE_SET` rejected by firmware | `DefineZoneUseCase` receives ERROR response | Show error, restore previous zone in UI |
| Session History record older than 90 days | `SessionHistoryRepository` scheduled cleanup (WorkManager, daily) | Delete records with `startTimestampUtcMs < now - 90 days` |

All ERROR messages from the firmware are logged with structured tags:
```kotlin
Log.e("Protocol", "ERROR code=${error.code} name=${error.name} failedMessageId=${error.failedMessageId}")
```

---

## Threading Model

All operations follow Android's structured concurrency rules:

| Operation | Dispatcher |
|---|---|
| USB bulk read/write | `Dispatchers.IO` |
| JSON encode/decode | `Dispatchers.Default` |
| Room database queries | `Dispatchers.IO` (Room enforces this) |
| Trilateration computation | `Dispatchers.Default` |
| UI state updates | `Dispatchers.Main` (via `StateFlow`) |
| Heartbeat timer | `Dispatchers.IO` (coroutine `delay`) |

`StrictMode` is enabled in debug builds to catch accidental main-thread I/O.

## Testing Strategy

### Dual Testing Approach

Both unit/example-based tests and property-based tests are used. They are complementary: example tests cover specific scenarios and integration points; property tests cover universal invariants across the full input space.

### Unit and Integration Tests

- **Connection state machine**: Test each state transition with mock USB transport responses (PAIR_RESPONSE, SESSION_ACK, ERROR codes).
- **Heartbeat timeout**: Use a fake clock to advance time and verify session termination after 3 missed heartbeats.
- **Offline schedule queuing**: Verify pending schedules are sent on reconnection.
- **Coverage overlay clearing**: Verify the overlay resets when a new session starts.
- **Map screen navigation**: Verify RANGING_START is sent on open and RANGING_STOP on leave.
- **Error handling**: Inject each firmware ERROR code and verify the correct UI notification and state revert.
- **Repository mappers**: Unit-test all `Entity ↔ Domain ↔ UI` mapper functions.

### Property-Based Tests

Each property in the Correctness Properties section is implemented as a property-based test using a Kotlin PBT library (e.g., Kotest property testing). Minimum 100 iterations per property. Tests are tagged with the feature and property number:

```
Feature: lawn-mower-control, Property N: <property_text>
```

### ESP32 Host-Side Tests

- Parser/serialiser round-trip tests for all message types (including new types).
- State machine unit tests for `session_sm` and `ranging_sm`.
- `trilateration.c` unit tests with known anchor/distance/position test vectors.
- Hardware-in-the-loop smoke tests: pair, ranging start/stop, reconnection.

---

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Redundancy Analysis

Before listing properties, redundancies are resolved:

- **1.8 and 9.6** both test version rejection. Combined into Property 1.
- **5.2 and 5.3** both test ZONE_SET schema. Combined into Property 6 (schema + ordering).
- **9.1 and 9.4** both test envelope conformance. Property 11 covers outgoing envelope completeness; Property 12 covers firmware rejection of malformed envelopes.
- **3.2, 3.5, 3.6** are UI state rendering properties that can be combined into a single map state rendering property (Property 4).
- **6.2 and 6.3** are related but distinct: schema validation vs. accumulation invariant. Kept separate.
- **8.1 and 8.2** are distinct: record completeness vs. sort order. Kept separate.

---

### Property 1: Protocol Version Rejection

*For any* incoming envelope whose `protocolVersion` is strictly less than the App's supported version, the App SHALL reject the envelope and respond with an ERROR message using code `1002` (`ERR_UNSUPPORTED_VERSION`), and the envelope SHALL NOT be processed further.

**Validates: Requirements 1.8, 9.6**

---

### Property 2: Trilateration Validity

*For any* set of 3 or more anchors at distinct known 2D positions and a corresponding set of positive distance measurements (in mm), the trilateration solver SHALL produce a 2D position that is within a bounded error tolerance of the true position, and SHALL complete within 200 ms of receiving the `RANGING_SAMPLE` message.

**Validates: Requirements 2.2**

---

### Property 3: Low-Quality Sample Rejection

*For any* `RANGING_SAMPLE` message whose `quality` field is strictly less than 0.5, the App SHALL discard the sample and the displayed mower position SHALL remain unchanged from its last valid value.

**Validates: Requirements 2.4**

---

### Property 4: Map State Rendering Invariants

*For any* map state containing a non-empty list of anchors, an optional zone polygon, and an optional list of coverage segments:
- Every anchor in the list SHALL appear as a labeled marker in the rendered map state.
- If a zone is defined, it SHALL appear as a closed polygon overlay.
- If coverage segments are present, they SHALL appear as a filled overlay visually distinct from the unmowed area.

**Validates: Requirements 3.2, 3.5, 3.6**

---

### Property 5: Coordinate Conversion Round-Trip

*For any* screen coordinate within the canvas bounds, converting to map coordinates (mm) and back to screen coordinates SHALL produce a result within 1 pixel of the original screen coordinate, given the same zoom scale and pan offset.

**Validates: Requirements 4.1, 4.2**

---

### Property 6: ZONE_SET Message Schema and Vertex Ordering

*For any* zone polygon with 3 or more vertices defined by the user, the resulting `ZONE_SET` message SHALL:
- Contain a `vertices` array with the same number of elements as the user-defined polygon.
- Preserve the original vertex order.
- Represent each vertex as an object with integer fields `xMm` and `yMm` in millimetres relative to the Map origin.

**Validates: Requirements 5.2, 5.3**

---

### Property 7: Zone Persistence Round-Trip

*For any* zone polygon saved to the local Room database, loading the zone from the database SHALL produce a zone with identical `id` and `vertices` (same count, same order, same mm values).

**Validates: Requirements 5.4**

---

### Property 8: Out-of-Zone MOVE_TO Blocking

*For any* defined zone polygon and any map coordinate that lies strictly outside the zone boundary, the App SHALL block the `MOVE_TO` command and display an "outside zone" warning instead of sending the message to the firmware.

**Validates: Requirements 5.7**

---

### Property 9: Coverage Accumulation Invariant

*For any* sequence of `COVERAGE_UPDATE` messages received during a session, the accumulated coverage overlay SHALL equal the union of all segments from all received updates. No segment from any update SHALL be lost or duplicated in the accumulated state.

**Validates: Requirements 6.3**

---

### Property 10: Coverage Percentage Correctness

*For any* defined zone polygon and any set of accumulated coverage segments, the displayed coverage percentage SHALL equal the ratio of the zone area covered by the segments to the total zone area, within a tolerance of ±1% (due to rasterisation grid resolution).

**Validates: Requirements 6.6**

---

### Property 11: Outgoing Envelope Completeness

*For any* outgoing message of type `MOVE_TO`, `ZONE_SET`, or `SCHEDULE_SET`, the serialised envelope SHALL contain all six required fields: `protocolVersion`, `messageType`, `messageId`, `sessionId`, `timestampMs`, and `payload`.

**Validates: Requirements 9.1**

---

### Property 12: Monotonically Increasing Message IDs

*For any* sequence of outgoing messages within a single session, the `messageId` of each message SHALL be strictly greater than the `messageId` of the preceding message. The counter SHALL reset to 1 at the start of each new session.

**Validates: Requirements 9.2**

---

### Property 13: Firmware Rejects Unauthenticated Control Messages

*For any* `MOVE_TO`, `ZONE_SET`, or `SCHEDULE_SET` message received by the firmware outside of an active authenticated session, the firmware SHALL respond with an ERROR message using code `1003` (`ERR_UNAUTHORIZED`) and SHALL NOT execute the command.

**Validates: Requirements 9.3**

---

### Property 14: Firmware Rejects Malformed Envelopes

*For any* envelope with one or more missing required fields (`protocolVersion`, `messageType`, `messageId`, `sessionId`, `timestampMs`, `payload`) or with an invalid payload schema for the declared `messageType`, the firmware SHALL respond with an ERROR message using code `1001` (`ERR_INVALID_SCHEMA`) and SHALL NOT process the message.

**Validates: Requirements 9.4**

---

### Property 15: SCHEDULE_SET Message Schema

*For any* schedule saved by the user, the resulting `SCHEDULE_SET` message payload SHALL contain: `scheduleId` (non-empty string), `startTimeUtcHhmm` (string matching `HH:MM` format), `daysOfWeek` (array of integers each in [0, 6] with at least one element), and `zoneId` (string or null).

**Validates: Requirements 7.3**

---

### Property 16: Schedule Persistence Round-Trip

*For any* schedule saved to the local Room database, loading the schedule from the database SHALL produce a schedule with identical `scheduleId`, `startTimeUtcHhmm`, `daysOfWeek`, and `zoneId`.

**Validates: Requirements 7.4**

---

### Property 17: Session History Record Completeness

*For any* completed mowing session, the persisted `SessionRecord` SHALL contain: a non-null `startTimestampUtcMs`, a non-null `endTimestampUtcMs` greater than `startTimestampUtcMs`, a `durationSeconds` equal to `(endTimestampUtcMs - startTimestampUtcMs) / 1000`, a non-negative `totalDistanceMm`, and a `coveragePercent` in [0.0, 1.0].

**Validates: Requirements 8.1**

---

### Property 18: Session History Sort Order

*For any* set of session history records loaded from the database, the records SHALL be ordered by `startTimestampUtcMs` in strictly descending order (most recent first).

**Validates: Requirements 8.2**

---

### Property 19: Session History 90-Day Retention

*For any* session history record whose `startTimestampUtcMs` is within 90 days of the current time, the record SHALL NOT be deleted by the automatic cleanup process. Records whose `startTimestampUtcMs` is more than 90 days before the current time SHALL be eligible for deletion.

**Validates: Requirements 8.4**

---

### Property 20: Error Message Logging Completeness

*For any* ERROR message received from the firmware, the App SHALL log a structured entry containing the `code`, `name`, and `failedMessageId` fields from the error payload, and SHALL display a human-readable notification to the user.

**Validates: Requirements 10.2**

---

### Property 21: Offline Data Availability

*For any* disconnected app state, all data previously persisted to the local Room database (zone boundaries, coverage segments, session history records, schedules, anchor configurations) SHALL be loadable and displayable without requiring an active USB connection.

**Validates: Requirements 10.5**

---

### Property 22: Minimum Anchor Count for Ranging

*For any* anchor configuration with fewer than 3 anchors, the App SHALL NOT enable ranging-based positioning and SHALL NOT send a `RANGING_START` message. For any configuration with 3 or more anchors, ranging SHALL be available.

**Validates: Requirements 2.7**

---

### Property 23: Coverage Persistence Round-Trip

*For any* set of coverage segments accumulated during a session and persisted to the local Room database, reloading the segments from the database SHALL produce a set with identical segment count and identical `fromXMm`, `fromYMm`, `toXMm`, `toYMm` values for each segment.

**Validates: Requirements 6.4**

---

### Property 24: Emulator Normal Path Progression

*For any* configured waypoint path and any two consecutive `RANGING_SAMPLE` messages emitted by `EmulatedMowerDevice` in the `Normal` scenario, the Euclidean distance from the reported position to the next waypoint SHALL be less than or equal to the distance from the previous reported position to that same waypoint (i.e., the simulated mower monotonically approaches each waypoint).

**Validates: Emulator Architecture — Normal scenario**

---

### Property 25: Emulator Drift Accumulation

*For any* `Drift` scenario with a positive `driftRateMmPerSec`, the offset between the emulator's reported position and the true path position SHALL be strictly greater at time `t₂` than at time `t₁` for any `t₂ > t₁` within the active drift window.

**Validates: Emulator Architecture — Drift scenario**

---

### Property 26: Emulator Stuck Position Invariant

*For any* `Stuck` scenario with duration `D` ms, all `RANGING_SAMPLE` messages emitted by `EmulatedMowerDevice` during the interval `[t_stuck, t_stuck + D]` SHALL report identical `xMm` and `yMm` values equal to the position at which the stuck scenario was activated.

**Validates: Emulator Architecture — Stuck scenario**

---

### Property 27: Emulator Signal Interference Quality Bound

*For any* `SignalInterference` scenario, every `RANGING_SAMPLE` message emitted by `EmulatedMowerDevice` during the active interference window SHALL have a `quality` value strictly less than 0.5, causing the App to discard all such samples and leave the displayed mower position unchanged.

**Validates: Emulator Architecture — Signal Interference scenario**

---

### Property 28: Emulator Signal Loss Gap

*For any* `SignalLoss` scenario with `durationMs` ≥ 3000, `EmulatedMowerDevice` SHALL emit no `RANGING_SAMPLE` messages for the entire duration of the signal-loss window, guaranteeing that the App's 3000 ms timeout fires and sets `isPositionLost = true`.

**Validates: Emulator Architecture — Signal Loss scenario**

---

### Property 29: Emulator Busy Response

*For any* `MOVE_TO` command sent to `EmulatedMowerDevice` while a `Busy` scenario is active, the emulator SHALL respond with an `ERROR` message using code `1006` (`ERR_BUSY`) and SHALL NOT advance the simulated mower's destination, mirroring the real firmware's busy behavior.

**Validates: Emulator Architecture — Busy scenario**
