# Android <-> ESP32 UWB Message Contract

This file is the executable contract for communication between:
- Android app (Jetpack Compose client)
- ESP32 firmware (ESP-IDF + FreeRTOS + DW1000/DW3000)

## Versioning policy
- `protocolVersion` starts at `1`.
- Additive fields are backward-compatible.
- Breaking payload changes require a new major protocol version.
- Current supported version: **2** (bumped from 1 to introduce `MOVE_TO`, `ZONE_SET`, `COVERAGE_UPDATE`, and `SCHEDULE_SET`).

## REST emulator service contract

For Android emulator mode, the app can talk to a REST service instead of direct USB transport.

- Base URL: `http://10.0.2.2:8080` (from Android Emulator to host machine)
- Endpoints:
  - `POST /api/v1/device/connect`
  - `POST /api/v1/device/disconnect`
  - `POST /api/v1/device/send` (body is protocol envelope JSON)
  - `GET /api/v1/device/messages?sinceId=<messageId>`
  - `GET /api/v1/emulator/state`
  - `POST /api/v1/emulator/scenario/activate`
  - `POST /api/v1/emulator/scenario/clear`

`/api/v1/device/messages` must return protocol envelopes compatible with this document.

## Canonical envelope

Every message — in both directions — MUST include all six top-level fields.

| Field | Type | Description |
|---|---|---|
| `protocolVersion` | integer | Protocol version. Both sides reject peers advertising an incompatible version with `ERR_UNSUPPORTED_VERSION` (1002). |
| `messageType` | string | One of the message type identifiers listed below. |
| `messageId` | uint32 | Monotonically increasing per sender per session. Resets to 1 on each new session. |
| `sessionId` | string | Opaque identifier for the active pair session. Empty string (`""`) before a session is established. |
| `timestampMs` | uint64 | Monotonic milliseconds from sender boot / session start. Not a wall-clock value. |
| `payload` | object | Message-type-specific payload object. Never `null`; use `{}` for empty payloads. |

```json
{
  "protocolVersion": 2,
  "messageType": "RANGING_START",
  "messageId": 42,
  "sessionId": "a1b2c3d4",
  "timestampMs": 125034,
  "payload": {}
}
```

## Message types and payloads

### HELLO
Direction: App → Firmware  
Used on initial discovery/connection before a session is established.

| Payload field | Type | Required | Description |
|---|---|---|---|
| `deviceType` | string | yes | Sender identity. App sends `"android"`. |
| `capabilities` | array of string | yes | Feature flags the sender supports. |

```json
{
  "protocolVersion": 2,
  "messageType": "HELLO",
  "messageId": 1,
  "sessionId": "",
  "timestampMs": 100,
  "payload": {
    "deviceType": "android",
    "capabilities": ["uwb_ranging", "heartbeat"]
  }
}
```

---

### PAIR_REQUEST
Direction: App → Firmware

| Payload field | Type | Required | Description |
|---|---|---|---|
| `pairingNonce` | string | yes | Random nonce for this pairing attempt. |
| `appInstanceId` | string | yes | Stable identifier for this app installation. |

```json
{
  "protocolVersion": 2,
  "messageType": "PAIR_REQUEST",
  "messageId": 2,
  "sessionId": "",
  "timestampMs": 220,
  "payload": {
    "pairingNonce": "8f4d3b2a",
    "appInstanceId": "phone-001"
  }
}
```

---

### PAIR_RESPONSE
Direction: Firmware → App

| Payload field | Type | Required | Description |
|---|---|---|---|
| `accepted` | boolean | yes | `true` if the firmware accepted the pairing request. |
| `deviceInstanceId` | string | yes | Stable identifier for this firmware instance. |

```json
{
  "protocolVersion": 2,
  "messageType": "PAIR_RESPONSE",
  "messageId": 3,
  "sessionId": "a1b2c3d4",
  "timestampMs": 300,
  "payload": {
    "accepted": true,
    "deviceInstanceId": "esp32-anchor-01"
  }
}
```

---

### SESSION_START / SESSION_ACK
Direction: App → Firmware (SESSION_START), Firmware → App (SESSION_ACK)

**SESSION_START payload:**

| Payload field | Type | Required | Description |
|---|---|---|---|
| `mode` | string | yes | Session mode. Currently `"single_anchor"`. |

**SESSION_ACK payload:**

| Payload field | Type | Required | Description |
|---|---|---|---|
| `ok` | boolean | yes | `true` if the firmware accepted the session. |

```json
{
  "protocolVersion": 2,
  "messageType": "SESSION_START",
  "messageId": 4,
  "sessionId": "a1b2c3d4",
  "timestampMs": 350,
  "payload": {
    "mode": "single_anchor"
  }
}
```

```json
{
  "protocolVersion": 2,
  "messageType": "SESSION_ACK",
  "messageId": 5,
  "sessionId": "a1b2c3d4",
  "timestampMs": 370,
  "payload": {
    "ok": true
  }
}
```

---

### RANGING_START / RANGING_STOP
Direction: App → Firmware

**RANGING_START payload:**

| Payload field | Type | Required | Description |
|---|---|---|---|
| `sampleRateHz` | integer | yes | Requested ranging sample rate. Minimum 5. |
| `channel` | integer | yes | UWB channel number. |
| `preamble` | integer | yes | UWB preamble length. |

**RANGING_STOP payload:**

| Payload field | Type | Required | Description |
|---|---|---|---|
| `reason` | string | yes | Stop reason. `"user_request"` or `"session_end"`. |

```json
{
  "protocolVersion": 2,
  "messageType": "RANGING_START",
  "messageId": 6,
  "sessionId": "a1b2c3d4",
  "timestampMs": 420,
  "payload": {
    "sampleRateHz": 10,
    "channel": 5,
    "preamble": 128
  }
}
```

```json
{
  "protocolVersion": 2,
  "messageType": "RANGING_STOP",
  "messageId": 12,
  "sessionId": "a1b2c3d4",
  "timestampMs": 1500,
  "payload": {
    "reason": "user_request"
  }
}
```

---

### RANGING_SAMPLE
Direction: Firmware → App

| Payload field | Type | Required | Description |
|---|---|---|---|
| `distanceMm` | integer | yes | Measured distance from the mower tag to the reporting anchor, in millimetres. |
| `quality` | float | yes | Ranging quality score in the range [0.0, 1.0]. Samples with `quality < 0.5` are discarded by the App. |
| `rssiDbm` | integer | yes | Received signal strength in dBm (negative value). |
| `sequence` | uint32 | yes | Per-anchor monotonic sequence number for this ranging exchange. |

```json
{
  "protocolVersion": 2,
  "messageType": "RANGING_SAMPLE",
  "messageId": 99,
  "sessionId": "a1b2c3d4",
  "timestampMs": 1510,
  "payload": {
    "distanceMm": 2375,
    "quality": 0.92,
    "rssiDbm": -78,
    "sequence": 87
  }
}
```

---

### HEARTBEAT
Direction: App → Firmware (request), Firmware → App (response)

| Payload field | Type | Required | Description |
|---|---|---|---|
| `status` | string | yes | `"ok"` in normal operation. |

```json
{
  "protocolVersion": 2,
  "messageType": "HEARTBEAT",
  "messageId": 100,
  "sessionId": "a1b2c3d4",
  "timestampMs": 2000,
  "payload": {
    "status": "ok"
  }
}
```

---

### ERROR
Direction: either side

| Payload field | Type | Required | Description |
|---|---|---|---|
| `code` | integer | yes | Numeric error code from the table below. |
| `name` | string | yes | Human-readable error name matching the code. |
| `detail` | string | yes | Free-text description of the specific failure. |
| `failedMessageId` | uint32 | yes | `messageId` of the message that triggered this error. `0` if not applicable. |

```json
{
  "protocolVersion": 2,
  "messageType": "ERROR",
  "messageId": 101,
  "sessionId": "a1b2c3d4",
  "timestampMs": 2010,
  "payload": {
    "code": 1007,
    "name": "ERR_RADIO_FAILURE",
    "detail": "rx timeout exceeded",
    "failedMessageId": 6
  }
}
```

---

## New message types (protocol version 2)

The following four message types were introduced in protocol version 2. Both the App and the Firmware MUST reject connections from peers advertising `protocolVersion < 2` with error code `1002` (`ERR_UNSUPPORTED_VERSION`) when these message types are in use.

---

### MOVE_TO
Direction: App → Firmware  
Requires: active authenticated session (`AUTHENTICATED` or `RANGING` state).

Instructs the firmware to autonomously navigate the mower to the specified 2D coordinate. The firmware rejects this message outside an authenticated session with `ERR_UNAUTHORIZED` (1003). If the mower is already executing a movement command, the firmware responds with `ERR_BUSY` (1006).

| Payload field | Type | Required | Nullable | Description |
|---|---|---|---|---|
| `targetXMm` | integer | yes | no | Target X coordinate in millimetres, relative to the Map origin. |
| `targetYMm` | integer | yes | no | Target Y coordinate in millimetres, relative to the Map origin. |

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

---

### ZONE_SET
Direction: App → Firmware  
Requires: active authenticated session (`AUTHENTICATED` or `RANGING` state).

Defines or replaces the mowing zone polygon. The firmware rejects this message outside an authenticated session with `ERR_UNAUTHORIZED` (1003). Sending a new `ZONE_SET` replaces any previously stored zone on the firmware.

| Payload field | Type | Required | Nullable | Description |
|---|---|---|---|---|
| `zoneId` | string | yes | no | Unique identifier for this zone. Used to correlate zone references in `SCHEDULE_SET`. |
| `vertices` | array of vertex objects | yes | no | Ordered list of polygon vertices. Minimum 3 elements. Vertex order is preserved and defines the polygon winding. |

**Vertex object schema:**

| Field | Type | Required | Nullable | Description |
|---|---|---|---|---|
| `xMm` | integer | yes | no | Vertex X coordinate in millimetres, relative to the Map origin. |
| `yMm` | integer | yes | no | Vertex Y coordinate in millimetres, relative to the Map origin. |

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

---

### COVERAGE_UPDATE
Direction: Firmware → App  
Sent periodically by the firmware during an active mowing session to report newly mowed path segments since the last update.

| Payload field | Type | Required | Nullable | Description |
|---|---|---|---|---|
| `segments` | array of segment objects | yes | no | List of mowed path segments since the previous `COVERAGE_UPDATE`. May be empty (`[]`) if no new coverage since the last message. |

**Segment object schema:**

| Field | Type | Required | Nullable | Description |
|---|---|---|---|---|
| `fromXMm` | integer | yes | no | Start X coordinate of the segment in millimetres, relative to the Map origin. |
| `fromYMm` | integer | yes | no | Start Y coordinate of the segment in millimetres, relative to the Map origin. |
| `toXMm` | integer | yes | no | End X coordinate of the segment in millimetres, relative to the Map origin. |
| `toYMm` | integer | yes | no | End Y coordinate of the segment in millimetres, relative to the Map origin. |

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

---

### SCHEDULE_SET
Direction: App → Firmware  
Requires: active authenticated session (`AUTHENTICATED` or `RANGING` state).

Creates, updates, or deletes a recurring mowing schedule. To delete a schedule, send this message with `deleted: true` and the corresponding `scheduleId`. The firmware rejects this message outside an authenticated session with `ERR_UNAUTHORIZED` (1003).

| Payload field | Type | Required | Nullable | Description |
|---|---|---|---|---|
| `scheduleId` | string | yes | no | Unique identifier for this schedule. Stable across create/update/delete operations. |
| `startTimeUtcHhmm` | string | yes | no | Start time in UTC, formatted as `"HH:MM"` (24-hour clock). Example: `"07:30"`. |
| `daysOfWeek` | array of integer | yes | no | Days of the week on which the schedule is active. Each element is an integer 0–6 where 0 = Sunday, 1 = Monday, …, 6 = Saturday. Minimum 1 element. |
| `zoneId` | string | yes | **yes** | ID of the zone to mow. `null` means use the currently active zone (or the full area if no zone is defined). |
| `deleted` | boolean | yes | no | `true` signals that this schedule should be removed from the firmware. `false` for create/update. |

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

Delete example:

```json
{
  "protocolVersion": 2,
  "messageType": "SCHEDULE_SET",
  "messageId": 204,
  "sessionId": "a1b2c3d4",
  "timestampMs": 5300,
  "payload": {
    "scheduleId": "sched-001",
    "startTimeUtcHhmm": "07:30",
    "daysOfWeek": [1, 3, 5],
    "zoneId": null,
    "deleted": true
  }
}
```

---

## Error codes

| Code | Name | Description |
|---|---|---|
| `1000` | `ERR_UNKNOWN_MESSAGE` | The `messageType` is not recognised by the receiver. |
| `1001` | `ERR_INVALID_SCHEMA` | A required envelope field is missing, or the payload does not match the expected schema for the given `messageType`. |
| `1002` | `ERR_UNSUPPORTED_VERSION` | The `protocolVersion` in the envelope is not supported by the receiver. |
| `1003` | `ERR_UNAUTHORIZED` | A control message (`MOVE_TO`, `ZONE_SET`, `SCHEDULE_SET`) was received outside an active authenticated session. |
| `1004` | `ERR_SESSION_NOT_FOUND` | The `sessionId` in the envelope does not match any active session. |
| `1005` | `ERR_TIMEOUT` | An expected response was not received within the configured timeout. |
| `1006` | `ERR_BUSY` | The firmware cannot accept a `MOVE_TO` command because it is already executing a movement. |
| `1007` | `ERR_RADIO_FAILURE` | A UWB radio error occurred (e.g. RX/TX timeout, CRC failure). |
| `1008` | `ERR_INTERNAL` | An unexpected internal firmware error occurred. |

---

## Timeout and retry defaults

| Message | Timeout | Retries |
|---|---|---|
| `PAIR_REQUEST` | 3000 ms | 3 |
| `SESSION_START` | 2000 ms | 3 |
| `RANGING_START` | 1500 ms | 2 |
| `HEARTBEAT` | 1000 ms interval | disconnect after 3 misses |

---

## Validation rules

- Reject any envelope with missing required top-level fields (`protocolVersion`, `messageType`, `messageId`, `sessionId`, `timestampMs`, `payload`).
- Reject any envelope whose `protocolVersion` is lower than the receiver's supported version; respond with `ERR_UNSUPPORTED_VERSION` (1002).
- Reject any envelope with a non-monotonic `messageId` for the same sender within a session.
- Reject any payload with missing required fields, invalid enum values, or out-of-range numeric values; respond with `ERR_INVALID_SCHEMA` (1001).
- Reject `MOVE_TO`, `ZONE_SET`, and `SCHEDULE_SET` messages received outside an active authenticated session; respond with `ERR_UNAUTHORIZED` (1003).
- `ZONE_SET` payloads with fewer than 3 vertices MUST be rejected with `ERR_INVALID_SCHEMA` (1001).
- `SCHEDULE_SET` payloads with an empty `daysOfWeek` array MUST be rejected with `ERR_INVALID_SCHEMA` (1001).
- `SCHEDULE_SET` payloads with `daysOfWeek` values outside the range 0–6 MUST be rejected with `ERR_INVALID_SCHEMA` (1001).
- Return `ERROR` with a canonical code for all rejections.

---

## Determinism rules for implementers

- Treat `timestampMs` as monotonic, not wall clock.
- Keep `messageId` strictly increasing for each sender within a session.
- Do not reuse `sessionId` after session termination.
- Serialize fields in stable order if using binary framing.
- `zoneId` in `SCHEDULE_SET` is the only nullable payload field in the protocol; all other payload fields are non-nullable.

---

## Trilateration test vectors

These test vectors are shared between the Android `TrilaterationSolver.kt` (Gauss-Newton solver) and the ESP32 `trilateration.c` (least-squares solver). Both implementations MUST produce a result within **50 mm** of the expected position for each vector.

All coordinates and distances are in **millimetres (mm)**. Anchor positions are given as `(xMm, yMm)`. The expected mower position is the ground-truth point used to generate the distances (distances are Euclidean, no noise added).

### Vector 1 — Equilateral triangle anchors, interior point

| # | Anchor ID | xMm | yMm | Distance to mower (mm) |
|---|---|---|---|---|
| A0 | `anchor-0` | 0 | 0 | 3606 |
| A1 | `anchor-1` | 6000 | 0 | 3606 |
| A2 | `anchor-2` | 3000 | 5196 | 2500 |

Expected mower position: `xMm = 3000`, `yMm = 2165`

Derivation: anchors form an equilateral triangle with side 6000 mm. The mower is placed at (3000, 2165), which is near the centroid. Distances are computed as `sqrt((x - xᵢ)² + (y - yᵢ)²)` rounded to the nearest integer.

---

### Vector 2 — Right-angle anchors, off-centre point

| # | Anchor ID | xMm | yMm | Distance to mower (mm) |
|---|---|---|---|---|
| A0 | `anchor-0` | 0 | 0 | 5000 |
| A1 | `anchor-1` | 8000 | 0 | 5000 |
| A2 | `anchor-2` | 0 | 6000 | 5000 |

Expected mower position: `xMm = 4000`, `yMm = 3000`

Derivation: mower at (4000, 3000). Distance to A0 = sqrt(16000000 + 9000000) = sqrt(25000000) = 5000 mm exactly. Distance to A1 = sqrt((4000-8000)² + 9000000) = sqrt(16000000 + 9000000) = 5000 mm. Distance to A2 = sqrt(16000000 + (3000-6000)²) = sqrt(16000000 + 9000000) = 5000 mm.

---

### Vector 3 — Four anchors (over-determined), near-boundary point

| # | Anchor ID | xMm | yMm | Distance to mower (mm) |
|---|---|---|---|---|
| A0 | `anchor-0` | 0 | 0 | 1414 |
| A1 | `anchor-1` | 10000 | 0 | 9055 |
| A2 | `anchor-2` | 10000 | 8000 | 11402 |
| A3 | `anchor-3` | 0 | 8000 | 7071 |

Expected mower position: `xMm = 1000`, `yMm = 1000`

Derivation: mower at (1000, 1000). A0 = sqrt((1000)² + (1000)²) ≈ 1414 mm. A1 = sqrt((1000-10000)² + (1000)²) = sqrt(81000000 + 1000000) ≈ 9055 mm. A2 = sqrt((1000-10000)² + (1000-8000)²) = sqrt(81000000 + 49000000) ≈ 11402 mm. A3 = sqrt((1000)² + (1000-8000)²) = sqrt(1000000 + 49000000) ≈ 7071 mm.

---

### Vector 4 — Three anchors, centre of a 10 m × 8 m rectangle

| # | Anchor ID | xMm | yMm | Distance to mower (mm) |
|---|---|---|---|---|
| A0 | `anchor-0` | 0 | 0 | 6403 |
| A1 | `anchor-1` | 10000 | 0 | 6403 |
| A2 | `anchor-2` | 5000 | 8000 | 4000 |

Expected mower position: `xMm = 5000`, `yMm = 4000`

Derivation: mower at (5000, 4000). A0 = sqrt(25000000 + 16000000) = sqrt(41000000) ≈ 6403 mm. A1 = sqrt(25000000 + 16000000) ≈ 6403 mm. A2 = sqrt(0 + 16000000) = 4000 mm.

---

### Test vector usage

Both `TrilaterationSolver.kt` and `trilateration.c` unit tests MUST include all four vectors above. The acceptance criterion for each vector is:

```
abs(result.xMm - expected.xMm) <= 50  AND  abs(result.yMm - expected.yMm) <= 50
```

The solver MUST return an error / failure result (not a position) when fewer than 3 anchors are provided.
