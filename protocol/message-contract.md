# Android <-> ESP32 UWB Message Contract

This file is the executable contract for communication between:
- Android app (Jetpack Compose client)
- ESP32 firmware (ESP-IDF + FreeRTOS + DW1000/DW3000)

## Versioning policy
- `protocolVersion` starts at `1`.
- Additive fields are backward-compatible.
- Breaking payload changes require a new major protocol version.

## Canonical envelope

```json
{
  "protocolVersion": 1,
  "messageType": "RANGING_START",
  "messageId": 42,
  "sessionId": "a1b2c3d4",
  "timestampMs": 125034,
  "payload": {}
}
```

## Message types and payloads

### HELLO
Used on initial discovery/connection.

```json
{
  "protocolVersion": 1,
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

### PAIR_REQUEST

```json
{
  "protocolVersion": 1,
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

### PAIR_RESPONSE

```json
{
  "protocolVersion": 1,
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

### SESSION_START / SESSION_ACK

```json
{
  "protocolVersion": 1,
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
  "protocolVersion": 1,
  "messageType": "SESSION_ACK",
  "messageId": 5,
  "sessionId": "a1b2c3d4",
  "timestampMs": 370,
  "payload": {
    "ok": true
  }
}
```

### RANGING_START / RANGING_STOP

```json
{
  "protocolVersion": 1,
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
  "protocolVersion": 1,
  "messageType": "RANGING_STOP",
  "messageId": 12,
  "sessionId": "a1b2c3d4",
  "timestampMs": 1500,
  "payload": {
    "reason": "user_request"
  }
}
```

### RANGING_SAMPLE
Sent by ESP32 to Android.

```json
{
  "protocolVersion": 1,
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

### HEARTBEAT

```json
{
  "protocolVersion": 1,
  "messageType": "HEARTBEAT",
  "messageId": 100,
  "sessionId": "a1b2c3d4",
  "timestampMs": 2000,
  "payload": {
    "status": "ok"
  }
}
```

### ERROR

```json
{
  "protocolVersion": 1,
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

## Error codes
- `1000` `ERR_UNKNOWN_MESSAGE`
- `1001` `ERR_INVALID_SCHEMA`
- `1002` `ERR_UNSUPPORTED_VERSION`
- `1003` `ERR_UNAUTHORIZED`
- `1004` `ERR_SESSION_NOT_FOUND`
- `1005` `ERR_TIMEOUT`
- `1006` `ERR_BUSY`
- `1007` `ERR_RADIO_FAILURE`
- `1008` `ERR_INTERNAL`

## Timeout and retry defaults
- `PAIR_REQUEST`: timeout `3000 ms`, retries `3`
- `SESSION_START`: timeout `2000 ms`, retries `3`
- `RANGING_START`: timeout `1500 ms`, retries `2`
- `HEARTBEAT`: every `1000 ms`, disconnect after `3` misses

## Validation rules
- Reject envelope with missing required fields.
- Reject unsupported protocol version.
- Reject non-monotonic `messageId` per sender session.
- Reject payload with invalid enum or out-of-range numeric values.
- Return `ERROR` with canonical code for all rejections.

## Determinism rules for implementers
- Treat `timestampMs` as monotonic, not wall clock.
- Keep `messageId` strictly increasing for each sender.
- Do not reuse `sessionId` after session termination.
- Serialize fields in stable order if using binary framing.
