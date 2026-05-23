#pragma once
#include "esp_err.h"
#include <stddef.h>
#include <stdint.h>
esp_err_t usb_serial_init(void);
esp_err_t usb_serial_write(const uint8_t *data, size_t len);
esp_err_t usb_serial_read(uint8_t *buf, size_t buf_size, size_t *out_len);
