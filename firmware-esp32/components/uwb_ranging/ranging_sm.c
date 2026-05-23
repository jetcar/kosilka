#include "ranging_sm.h"
#include "esp_log.h"
static const char *TAG = "ranging_sm";
static ranging_state_t s_state = RANGING_STATE_STOPPED;
esp_err_t ranging_sm_init(void) { s_state = RANGING_STATE_STOPPED; return ESP_OK; }
esp_err_t ranging_sm_start(int rate) { ESP_LOGI(TAG, "start stub rate=%d", rate); s_state = RANGING_STATE_ACTIVE; return ESP_OK; }
esp_err_t ranging_sm_stop(void) { s_state = RANGING_STATE_STOPPED; return ESP_OK; }
ranging_state_t ranging_sm_get_state(void) { return s_state; }
