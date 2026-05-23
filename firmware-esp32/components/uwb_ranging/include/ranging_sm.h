#pragma once
#include "esp_err.h"
typedef enum { RANGING_STATE_STOPPED, RANGING_STATE_STARTING, RANGING_STATE_ACTIVE, RANGING_STATE_STOPPING } ranging_state_t;
esp_err_t ranging_sm_init(void);
esp_err_t ranging_sm_start(int sample_rate_hz);
esp_err_t ranging_sm_stop(void);
ranging_state_t ranging_sm_get_state(void);
