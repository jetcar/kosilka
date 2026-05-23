#include "msg_decode.h"
#include "esp_log.h"
static const char *TAG = "msg_decode";
esp_err_t msg_decode(const char *json, size_t len, decoded_message_t *out) {
    ESP_LOGI(TAG, "stub len=%d", (int)len); (void)json; (void)out;
    return ESP_ERR_NOT_SUPPORTED; // full impl in task 21.1
}
