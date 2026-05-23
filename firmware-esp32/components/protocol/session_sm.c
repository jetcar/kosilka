#include "session_sm.h"
#include "esp_log.h"
static const char *TAG = "session_sm";
static session_state_t s_state = SESSION_STATE_IDLE;
esp_err_t session_sm_init(void) { s_state = SESSION_STATE_IDLE; return ESP_OK; }
esp_err_t session_sm_handle_message(const decoded_message_t *msg) {
    ESP_LOGI(TAG, "stub type=%d", (int)msg->message_type); return ESP_OK; // full impl task 22.1
}
session_state_t session_sm_get_state(void) { return s_state; }
const char *session_sm_get_session_id(void) { return ""; }
