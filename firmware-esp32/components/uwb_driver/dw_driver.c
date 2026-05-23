#include "dw_driver.h"
#include "esp_log.h"

static const char *TAG = "dw_driver";

esp_err_t dw_driver_init(void) { ESP_LOGI(TAG, "init stub"); return ESP_OK; }
esp_err_t dw_driver_start_rx(void) { ESP_LOGI(TAG, "start_rx stub"); return ESP_OK; }
esp_err_t dw_driver_start_tx(const uint8_t *frame, size_t len) {
    ESP_LOGI(TAG, "start_tx stub len=%d", (int)len); (void)frame; return ESP_OK;
}
esp_err_t dw_driver_get_rx_frame(uint8_t *buf, size_t buf_size, size_t *out_len) {
    (void)buf; (void)buf_size; *out_len = 0; return ESP_OK;
}
void dw_driver_reset(void) { ESP_LOGI(TAG, "reset stub"); }
