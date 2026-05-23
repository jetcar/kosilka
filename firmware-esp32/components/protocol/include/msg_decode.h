#pragma once
#include "msg_types.h"
#include "esp_err.h"
#include <stddef.h>
#include <stdint.h>

typedef struct {
    int protocol_version;
    message_type_t message_type;
    uint32_t message_id;
    char session_id[64];
    uint64_t timestamp_ms;
    const char *payload_json; // points into source buffer; valid only during decode
} decoded_message_t;

esp_err_t msg_decode(const char *json, size_t len, decoded_message_t *out);
