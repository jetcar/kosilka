#include "usb_serial.h"
#include "esp_log.h"
static const char *TAG = "usb_serial";
esp_err_t usb_serial_init(void) { ESP_LOGI(TAG, "init stub"); return ESP_OK; }
esp_err_t usb_serial_write(const uint8_t *data, size_t len) { (void)data; (void)len; return ESP_OK; }
esp_err_t usb_serial_read(uint8_t *buf, size_t buf_size, size_t *out_len) { (void)buf; (void)buf_size; *out_len = 0; return ESP_OK; }
