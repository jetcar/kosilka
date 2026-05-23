#pragma once
#include "msg_types.h"
#include "esp_err.h"
#include <stddef.h>
#include <stdint.h>

typedef struct {
    message_type_t message_type;
    uint32_t message_id;
    const char *session_id;
    uint64_t timestamp_ms;
    const char *payload_json;
} outgoing_message_t;

esp_err_t msg_encode(const outgoing_message_t *msg, char *out_buf, size_t buf_size);
