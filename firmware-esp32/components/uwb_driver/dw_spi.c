#include "esp_log.h"
#include <stdint.h>
#include <stddef.h>
static const char *TAG = "dw_spi";
void dw_spi_init(void) { ESP_LOGI(TAG, "init stub"); }
void dw_spi_write(const uint8_t *data, size_t len) { (void)data; (void)len; }
void dw_spi_read(uint8_t *buf, size_t len) { (void)buf; (void)len; }
