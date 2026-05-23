# Requirements Document

## Introduction

The lawn-mower-control feature enables an Android application to connect to an ESP32-based autonomous lawn mower via USB Type-C, display a 2D map of the lawn, and allow the user to command the mower through tap-to-move navigation, zone definition, coverage tracking, scheduling, and session history. The mower's position is determined in real time using UWB multi-anchor ranging (3+ fixed DW1000/DW3000 anchors). The Android app communicates with the ESP32 firmware using the versioned JSON envelope protocol defined in `protocol/message-contract.md`, extended with new message types: `MOVE_TO`, `ZONE_SET`, `COVERAGE_UPDATE`, and `SCHEDULE_SET`.

## Glossary

- **App**: The Android application built with Kotlin, Jetpack Compose, Hilt, and Room.
- **Firmware**: The ESP32 software built with ESP-IDF and FreeRTOS that controls the mower hardware.
- **Mower**: The physical autonomous lawn mower device containing the ESP32 and UWB tag hardware.
- **Anchor**: A fixed UWB transceiver (DW1000 or DW3000) installed at a known position in the lawn area, used for positioning.
- **UWB Positioning System**: The set of 3 or more Anchors and the Mower's UWB tag that together compute the Mower's 2D position via time-of-flight ranging.
- **Protocol**: The versioned JSON envelope message contract defined in `protocol/message-contract.md`.
- **Session**: An authenticated, active communication session between the App and the Firmware, identified by a `sessionId`.
- **Map**: The 2D visual representation of the lawn area displayed in the App, rendered in a coordinate space aligned with the Anchor positions.
- **Zone**: A user-defined closed polygon boundary on the Map that defines the area the Mower should mow.
- **Coverage**: The set of Map cells or path segments that the Mower has already mowed during a Session.
- **Schedule**: A recurring time-based rule that instructs the Firmware to start a mowing session automatically.
- **Session History**: A persistent record of past mowing sessions stored in the App's local Room database.
- **MOVE_TO**: A new Protocol message type sent from the App to the Firmware carrying a target 2D coordinate.
- **ZONE_SET**: A new Protocol message type sent from the App to the Firmware carrying a Zone polygon definition.
- **COVERAGE_UPDATE**: A new Protocol message type sent from the Firmware to the App carrying incremental coverage data.
- **SCHEDULE_SET**: A new Protocol message type sent from the App to the Firmware carrying a Schedule definition.
- **Envelope**: The canonical JSON wrapper defined in `protocol/message-contract.md` containing `protocolVersion`, `messageType`, `messageId`, `sessionId`, `timestampMs`, and `payload`.

---

## Requirements

### Requirement 1 — USB Connection Management

**User Story:** As a user, I want the App to detect and connect to the Mower over USB Type-C so that I can control the Mower without relying on wireless pairing.

#### Acceptance Criteria

1. WHEN the Mower is physically connected via USB Type-C, THE App SHALL detect the USB device and initiate the connection handshake using the `HELLO` message as defined in the Protocol.
2. WHEN the App sends a `PAIR_REQUEST` message, THE App SHALL wait up to 3000 ms for a `PAIR_RESPONSE` and retry up to 3 times before reporting a connection failure to the user.
3. WHEN the Firmware returns a `PAIR_RESPONSE` with `accepted: true`, THE App SHALL send a `SESSION_START` message and transition to the connected state upon receiving `SESSION_ACK` with `ok: true`.
4. WHILE a Session is active, THE App SHALL send a `HEARTBEAT` message every 1000 ms to maintain the connection.
5. WHILE a Session is active, IF 3 consecutive `HEARTBEAT` messages receive no response within 1000 ms each, THEN THE App SHALL terminate the Session, release the USB connection, and display a disconnection notification to the user.
6. WHEN the user explicitly disconnects from within the App, THE App SHALL send a `RANGING_STOP` message with `reason: "user_request"` and then close the USB session.
7. IF the Firmware returns an `ERROR` message with code `1003` (`ERR_UNAUTHORIZED`) during pairing, THEN THE App SHALL display an authentication failure message and not retry automatically.
8. THE App SHALL reject any incoming Envelope with a `protocolVersion` lower than the App's supported version and respond with an `ERROR` message using code `1002` (`ERR_UNSUPPORTED_VERSION`).

---

### Requirement 2 — UWB Ranging and Live Mower Position

**User Story:** As a user, I want to see the Mower's real-time position on the Map so that I can monitor where the Mower is at all times.

#### Acceptance Criteria

1. WHEN a Session is active and the user opens the Map screen, THE App SHALL send a `RANGING_START` message to the Firmware with a `sampleRateHz` of at least 5.
2. WHEN the Firmware sends a `RANGING_SAMPLE` message, THE App SHALL compute the Mower's 2D position using trilateration from the distances reported by at least 3 Anchors and update the Mower's position on the Map within 200 ms of receiving the sample.
3. WHILE ranging is active, THE App SHALL display the Mower's computed 2D position as a marker on the Map, updated continuously as new `RANGING_SAMPLE` messages arrive.
4. IF a `RANGING_SAMPLE` message arrives with a `quality` value below 0.5, THEN THE App SHALL discard that sample and not update the displayed Mower position.
5. IF no `RANGING_SAMPLE` message is received for 3000 ms during an active ranging session, THEN THE App SHALL display a "position lost" indicator on the Map.
6. WHEN the user navigates away from the Map screen, THE App SHALL send a `RANGING_STOP` message to the Firmware.
7. THE UWB Positioning System SHALL require a minimum of 3 Anchors with known 2D coordinates configured in the App before ranging-based positioning is enabled.

---

### Requirement 3 — 2D Map Display

**User Story:** As a user, I want to view a 2D map of my lawn so that I can understand the layout and interact with the mowing area visually.

#### Acceptance Criteria

1. THE App SHALL display a 2D Map rendered in a coordinate space defined by the configured Anchor positions, with the Map origin aligned to the first Anchor's position.
2. THE App SHALL render each configured Anchor as a labeled marker on the Map at its configured 2D coordinate.
3. WHEN the Map is displayed, THE App SHALL support pinch-to-zoom and pan gestures to navigate the Map view.
4. THE App SHALL display the Map at a minimum resolution that allows the user to distinguish a 0.5 m × 0.5 m cell at the default zoom level.
5. WHILE a Zone is defined, THE App SHALL render the Zone boundary as a closed polygon overlay on the Map.
6. WHILE Coverage data is available for the current Session, THE App SHALL render the mowed area as a filled overlay on the Map, visually distinct from the unmowed area.

---

### Requirement 4 — Tap-to-Move Navigation

**User Story:** As a user, I want to tap a destination on the Map so that the Mower autonomously navigates to that point.

#### Acceptance Criteria

1. WHEN the user taps a point on the Map during an active Session, THE App SHALL convert the tapped screen coordinate to the Map's 2D coordinate space and send a `MOVE_TO` message to the Firmware containing the target `x` and `y` coordinates in millimetres.
2. THE `MOVE_TO` message payload SHALL include the fields `targetXMm` (integer, millimetres) and `targetYMm` (integer, millimetres), both relative to the Map origin.
3. WHEN the App sends a `MOVE_TO` message, THE App SHALL display a destination marker on the Map at the tapped coordinate until the Firmware confirms arrival or the command is superseded.
4. IF the user taps a new destination while a previous `MOVE_TO` command is in progress, THEN THE App SHALL send a new `MOVE_TO` message with the updated coordinates, replacing the previous destination marker.
5. IF the App is not in an active Session, THEN THE App SHALL disable tap-to-move interaction and display a "not connected" indicator to the user.
6. WHEN the Firmware sends an `ERROR` message with code `1006` (`ERR_BUSY`) in response to a `MOVE_TO` message, THE App SHALL display a "mower busy" notification and retain the previous destination marker.

---

### Requirement 5 — Mowing Zone Definition

**User Story:** As a user, I want to draw a mowing zone boundary on the Map so that the Mower only operates within the defined area.

#### Acceptance Criteria

1. WHEN the user activates zone-drawing mode, THE App SHALL allow the user to place a minimum of 3 vertices on the Map by tapping to define a closed polygon Zone.
2. WHEN the user confirms the Zone, THE App SHALL send a `ZONE_SET` message to the Firmware containing the Zone polygon as an ordered array of `{xMm, yMm}` vertex objects.
3. THE `ZONE_SET` message payload SHALL include the field `vertices` as an array of at least 3 objects, each with integer fields `xMm` and `yMm` in millimetres relative to the Map origin.
4. THE App SHALL persist the defined Zone in the local Room database so that the Zone is restored when the App is restarted.
5. WHEN the user edits or replaces an existing Zone, THE App SHALL send a new `ZONE_SET` message to the Firmware with the updated polygon and overwrite the persisted Zone in the database.
6. IF the Firmware returns an `ERROR` message in response to a `ZONE_SET` message, THEN THE App SHALL display the error to the user and retain the previously confirmed Zone.
7. WHILE a Zone is defined, THE App SHALL prevent the user from sending a `MOVE_TO` command to a coordinate outside the Zone boundary and display a "outside zone" warning instead.

---

### Requirement 6 — Coverage Tracking

**User Story:** As a user, I want to see which parts of the lawn have already been mowed during the current session so that I can verify complete coverage.

#### Acceptance Criteria

1. WHEN the Firmware sends a `COVERAGE_UPDATE` message, THE App SHALL update the Coverage overlay on the Map within 500 ms of receiving the message.
2. THE `COVERAGE_UPDATE` message payload SHALL include the field `segments` as an array of objects, each with fields `fromXMm`, `fromYMm`, `toXMm`, and `toYMm` (all integers in millimetres), representing mowed path segments since the last update.
3. THE App SHALL accumulate all `COVERAGE_UPDATE` segments received during a Session and render the union of all mowed segments as a filled overlay on the Map.
4. THE App SHALL persist the accumulated Coverage data for the current Session in the local Room database so that Coverage is restored if the App is restarted during an active Session.
5. WHEN a new Session starts, THE App SHALL clear the Coverage overlay and begin accumulating Coverage data fresh for the new Session.
6. THE App SHALL display the percentage of the defined Zone area that has been covered, calculated as the ratio of mowed area to total Zone area, updated each time a `COVERAGE_UPDATE` message is processed.

---

### Requirement 7 — Scheduling

**User Story:** As a user, I want to set recurring mowing schedules so that the Mower starts automatically at configured times without manual intervention.

#### Acceptance Criteria

1. WHEN the user creates a Schedule, THE App SHALL allow the user to specify a start time (hour and minute, 24-hour format), a set of days of the week (one or more), and an optional Zone to use.
2. WHEN the user saves a Schedule, THE App SHALL send a `SCHEDULE_SET` message to the Firmware containing the Schedule definition.
3. THE `SCHEDULE_SET` message payload SHALL include the fields `scheduleId` (string, unique per schedule), `startTimeUtcHhmm` (string, format `"HH:MM"`), `daysOfWeek` (array of integers 0–6, where 0 = Sunday), and `zoneId` (string, nullable).
4. THE App SHALL persist all Schedules in the local Room database and display the list of active Schedules to the user.
5. WHEN the user deletes a Schedule, THE App SHALL send a `SCHEDULE_SET` message to the Firmware with the corresponding `scheduleId` and a `deleted: true` field in the payload, and remove the Schedule from the local database.
6. IF the App is not connected to the Firmware when the user saves or deletes a Schedule, THEN THE App SHALL persist the change locally and send the `SCHEDULE_SET` message to the Firmware upon the next successful Session establishment.
7. THE App SHALL display a list of all active Schedules, showing the start time, days of the week, and associated Zone name for each Schedule.

---

### Requirement 8 — Session History

**User Story:** As a user, I want to review past mowing sessions so that I can track the history and performance of the Mower over time.

#### Acceptance Criteria

1. WHEN a mowing Session ends (either by user action or disconnection), THE App SHALL persist a Session History record in the local Room database containing: session start timestamp (UTC), session end timestamp (UTC), total duration in seconds, total distance travelled in millimetres, and the Coverage percentage achieved.
2. THE App SHALL display a list of Session History records sorted by start timestamp in descending order, showing start time, duration, and Coverage percentage for each record.
3. WHEN the user selects a Session History record, THE App SHALL display the Coverage map for that session, rendered using the persisted Coverage segments for that session.
4. THE App SHALL retain Session History records for a minimum of 90 days before automatic deletion.
5. THE App SHALL display a summary of the most recent Session on the home screen, including start time, duration, and Coverage percentage.

---

### Requirement 9 — Protocol Extension

**User Story:** As a developer, I want the new message types to conform to the existing Protocol contract so that both the App and Firmware can validate and process messages consistently.

#### Acceptance Criteria

1. THE App SHALL wrap all `MOVE_TO`, `ZONE_SET`, `COVERAGE_UPDATE`, and `SCHEDULE_SET` messages in the canonical Envelope with all required fields: `protocolVersion`, `messageType`, `messageId`, `sessionId`, `timestampMs`, and `payload`.
2. THE App SHALL use a strictly monotonically increasing `messageId` per sender per Session for all outgoing messages, including the new message types.
3. THE Firmware SHALL reject any `MOVE_TO`, `ZONE_SET`, or `SCHEDULE_SET` message received outside of an active authenticated Session and respond with an `ERROR` message using code `1003` (`ERR_UNAUTHORIZED`).
4. THE Firmware SHALL reject any Envelope with missing required fields or invalid payload schema for the new message types and respond with an `ERROR` message using code `1001` (`ERR_INVALID_SCHEMA`).
5. THE protocol documentation in `protocol/message-contract.md` SHALL be updated to include the full payload schemas, field descriptions, and example envelopes for `MOVE_TO`, `ZONE_SET`, `COVERAGE_UPDATE`, and `SCHEDULE_SET`.
6. WHERE the `protocolVersion` is incremented due to breaking payload changes in the new message types, THE App and Firmware SHALL both reject connections from peers advertising an incompatible `protocolVersion` using error code `1002` (`ERR_UNSUPPORTED_VERSION`).

---

### Requirement 10 — Error Handling and Resilience

**User Story:** As a user, I want the App to handle communication errors gracefully so that temporary failures do not result in data loss or an unresponsive interface.

#### Acceptance Criteria

1. IF the USB connection is interrupted during an active Session, THEN THE App SHALL detect the disconnection within 3000 ms, display a reconnection prompt to the user, and preserve all in-progress Coverage data in the local Room database.
2. WHEN the App receives an `ERROR` message from the Firmware, THE App SHALL log the error code, name, and `failedMessageId` with structured tags and display a human-readable error notification to the user.
3. IF the App fails to deliver a `MOVE_TO` or `ZONE_SET` message after the configured retry attempts, THEN THE App SHALL notify the user that the command was not delivered and revert the Map to the last confirmed state.
4. THE App SHALL not block the main thread during USB I/O, JSON serialization, or database operations; all such operations SHALL be performed on background coroutine dispatchers.
5. WHILE the App is in a disconnected state, THE App SHALL display all previously persisted Map data, Zone boundaries, Coverage overlays, and Session History records from the local Room database without requiring an active connection.
