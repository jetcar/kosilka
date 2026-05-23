#pragma once

// UWB channel 5 — default for DW1000/DW3000 indoor ranging
#define DW_CHANNEL 5
// Preamble length 128 — balances range and throughput
#define DW_PREAMBLE_LENGTH 128
// Antenna delay in DW time units (~15.65 ps/unit); calibrate per hardware revision
#define DW_ANTENNA_DELAY_TX 16436
#define DW_ANTENNA_DELAY_RX 16436
// Data rate 6.8 Mbps for short-range high-throughput ranging
#define DW_DATA_RATE_KBPS 6800
// STS mode disabled for basic TWR
#define DW_STS_MODE_DISABLED 0
// Max IEEE 802.15.4 frame size
#define DW_MAX_FRAME_SIZE 127
// Timeout before triggering recovery
#define DW_RX_TIMEOUT_MS 100
#define DW_TX_TIMEOUT_MS 50
// Max retries on RX/TX error
#define DW_MAX_RETRY_COUNT 3
