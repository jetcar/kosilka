#include "anchor_config.h"
#include "esp_log.h"
static const char *TAG = "anchor_config";
esp_err_t anchor_config_load(anchor_t *out_anchors, size_t *out_count) { (void)out_anchors; *out_count = 0; return ESP_OK; }
esp_err_t anchor_config_save(const anchor_t *anchors, size_t count) { (void)anchors; (void)count; return ESP_OK; }
