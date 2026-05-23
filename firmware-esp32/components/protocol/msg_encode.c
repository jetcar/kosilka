#include "msg_encode.h"
#include "esp_log.h"
static const char *TAG = "msg_encode";
esp_err_t msg_encode(const outgoing_message_t *msg, char *out_buf, size_t buf_size) {
    ESP_LOGI(TAG, "stub type=%d", (int)msg->message_type); (void)out_buf; (void)buf_size;
    return ESP_ERR_NOT_SUPPORTED; // full impl in task 21.1
}
