package com.kosilka.data.device.protocol

object ProtocolConstants {
    const val SUPPORTED_VERSION = 2

    // Message type string identifiers — must match protocol/message-contract.md
    const val TYPE_HELLO = "HELLO"
    const val TYPE_PAIR_REQUEST = "PAIR_REQUEST"
    const val TYPE_PAIR_RESPONSE = "PAIR_RESPONSE"
    const val TYPE_SESSION_START = "SESSION_START"
    const val TYPE_SESSION_ACK = "SESSION_ACK"
    const val TYPE_RANGING_START = "RANGING_START"
    const val TYPE_RANGING_STOP = "RANGING_STOP"
    const val TYPE_RANGING_SAMPLE = "RANGING_SAMPLE"
    const val TYPE_HEARTBEAT = "HEARTBEAT"
    const val TYPE_ERROR = "ERROR"
    const val TYPE_MOVE_TO = "MOVE_TO"
    const val TYPE_ZONE_SET = "ZONE_SET"
    const val TYPE_COVERAGE_UPDATE = "COVERAGE_UPDATE"
    const val TYPE_SCHEDULE_SET = "SCHEDULE_SET"

    // Error codes
    const val ERR_UNKNOWN_MESSAGE = 1000
    const val ERR_INVALID_SCHEMA = 1001
    const val ERR_UNSUPPORTED_VERSION = 1002
    const val ERR_UNAUTHORIZED = 1003
    const val ERR_SESSION_NOT_FOUND = 1004
    const val ERR_TIMEOUT = 1005
    const val ERR_BUSY = 1006
    const val ERR_RADIO_FAILURE = 1007
    const val ERR_INTERNAL = 1008
}
