package com.kosilka.core.emulator

sealed class EmulatorScenario {
    data object Normal : EmulatorScenario()
    data class Drift(val driftRateMmPerSec: Float) : EmulatorScenario()
    data class Stuck(val durationMs: Long) : EmulatorScenario()
    data class SignalInterference(val durationMs: Long) : EmulatorScenario()
    data class SignalLoss(val durationMs: Long) : EmulatorScenario()
    data class Busy(val durationMs: Long) : EmulatorScenario()
}
