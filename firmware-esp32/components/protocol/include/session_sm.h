#pragma once
#include "esp_err.h"
#include "msg_decode.h"

typedef enum {
    SESSION_STATE_IDLE, SESSION_STATE_HELLO_SENT, SESSION_STATE_PAIRING,
    SESSION_STATE_AUTHENTICATED, SESSION_STATE_RANGING, SESSION_STATE_ERROR
} session_state_t;

esp_err_t session_sm_init(void);
esp_err_t session_sm_handle_message(const decoded_message_t *msg);
session_state_t session_sm_get_state(void);
const char *session_sm_get_session_id(void);
