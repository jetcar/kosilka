#include "trilateration.h"
#include "esp_log.h"
static const char *TAG = "trilateration";
esp_err_t trilaterate(const anchor_t *anchors, size_t count, const float *distances_mm, point2d_t *out_pos) {
    if (count < 3) { ESP_LOGE(TAG, "need >= 3 anchors, got %d", (int)count); return ESP_ERR_INVALID_ARG; }
    // Full implementation in task 23.3
    (void)anchors; (void)distances_mm;
    out_pos->x_mm = 0.0f; out_pos->y_mm = 0.0f;
    return ESP_ERR_NOT_SUPPORTED;
}
