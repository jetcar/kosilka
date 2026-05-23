#include "frame_codec.h"
#include "esp_log.h"
#include <string.h>

static const char *TAG = "frame_codec";

// Frame format: [length: 4 bytes LE][json: length bytes]
#define FRAME_HEADER_SIZE 4

esp_err_t frame_encode(
    const char *json, size_t json_len,
    uint8_t *out_buf, size_t buf_size, size_t *out_len
) {
    if (json == NULL || out_buf == NULL || out_len == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    const size_t total = FRAME_HEADER_SIZE + json_len;
    if (total > buf_size) {
        ESP_LOGE(TAG, "frame_encode: buffer too small (%d < %d)", (int)buf_size, (int)total);
        return ESP_ERR_NO_MEM;
    }
    // Write 4-byte little-endian length prefix
    out_buf[0] = (uint8_t)(json_len & 0xFF);
    out_buf[1] = (uint8_t)((json_len >> 8) & 0xFF);
    out_buf[2] = (uint8_t)((json_len >> 16) & 0xFF);
    out_buf[3] = (uint8_t)((json_len >> 24) & 0xFF);
    memcpy(out_buf + FRAME_HEADER_SIZE, json, json_len);
    *out_len = total;
    return ESP_OK;
}

esp_err_t frame_decode(
    const uint8_t *buf, size_t buf_len,
    char *out_json, size_t json_buf_size, size_t *out_json_len
) {
    if (buf == NULL || out_json == NULL || out_json_len == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    if (buf_len < FRAME_HEADER_SIZE) {
        ESP_LOGE(TAG, "frame_decode: buffer too short for header (%d)", (int)buf_len);
        return ESP_ERR_INVALID_SIZE;
    }
    // Read 4-byte little-endian length prefix
    const size_t json_len =
        ((size_t)buf[0]) |
        ((size_t)buf[1] << 8) |
        ((size_t)buf[2] << 16) |
        ((size_t)buf[3] << 24);

    if (json_len > buf_len - FRAME_HEADER_SIZE) {
        ESP_LOGE(TAG, "frame_decode: declared length %d exceeds available data %d",
                 (int)json_len, (int)(buf_len - FRAME_HEADER_SIZE));
        return ESP_ERR_INVALID_SIZE;
    }
    if (json_len >= json_buf_size) {
        ESP_LOGE(TAG, "frame_decode: json_buf_size too small (%d <= %d)", (int)json_buf_size, (int)json_len);
        return ESP_ERR_NO_MEM;
    }
    memcpy(out_json, buf + FRAME_HEADER_SIZE, json_len);
    out_json[json_len] = '\0'; // null-terminate
    *out_json_len = json_len;
    return ESP_OK;
}
