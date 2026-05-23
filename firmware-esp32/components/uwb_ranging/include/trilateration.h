#pragma once
#include "esp_err.h"
#include <stddef.h>

typedef struct { const char *id; float x_mm; float y_mm; } anchor_t;
typedef struct { float x_mm; float y_mm; } point2d_t;

// Returns ESP_ERR_INVALID_ARG if count < 3
esp_err_t trilaterate(const anchor_t *anchors, size_t count, const float *distances_mm, point2d_t *out_pos);
