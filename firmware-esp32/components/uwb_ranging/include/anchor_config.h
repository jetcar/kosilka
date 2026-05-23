#pragma once
#include "trilateration.h"
#include "esp_err.h"
#include <stddef.h>
#define ANCHOR_CONFIG_MAX_ANCHORS 8
esp_err_t anchor_config_load(anchor_t *out_anchors, size_t *out_count);
esp_err_t anchor_config_save(const anchor_t *anchors, size_t count);
