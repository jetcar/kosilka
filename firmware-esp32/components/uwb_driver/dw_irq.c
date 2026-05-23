#include "esp_log.h"
static const char *TAG = "dw_irq";
void dw_irq_init(void) { ESP_LOGI(TAG, "init stub"); }
// ISR — minimal: capture signal only, defer work to task (task 23.1)
static void IRAM_ATTR dw_irq_handler(void *arg) { (void)arg; }
