package com.kosilka.core

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class UiEvent {
    data class Snackbar(val message: String) : UiEvent()
}

@Singleton
class UiEventBus @Inject constructor() {
    private val flow = MutableSharedFlow<UiEvent>(extraBufferCapacity = 64)

    val events: SharedFlow<UiEvent> = flow.asSharedFlow()

    suspend fun emit(event: UiEvent) {
        flow.emit(event)
    }
}
