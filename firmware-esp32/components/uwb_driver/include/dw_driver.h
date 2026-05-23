#pragma once
#include "esp_err.h"
#include <stdint.h>
#include <stddef.h>

esp_err_t dw_driver_init(void);
esp_err_t dw_driver_start_rx(void);
esp_err_t dw_driver_start_tx(const uint8_t *frame, size_t len);
esp_err_t dw_driver_get_rx_frame(uint8_t *buf, size_t buf_size, size_t *out_len);
void dw_driver_reset(void);
