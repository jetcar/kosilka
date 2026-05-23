#pragma once
#include "esp_err.h"
#include <stddef.h>
#include <stdint.h>
// 4-byte LE length prefix + JSON body framing
esp_err_t frame_encode(const char *json, size_t json_len, uint8_t *out_buf, size_t buf_size, size_t *out_len);
esp_err_t frame_decode(const uint8_t *buf, size_t buf_len, char *out_json, size_t json_buf_size, size_t *out_json_len);
