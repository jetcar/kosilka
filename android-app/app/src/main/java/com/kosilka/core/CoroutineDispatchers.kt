package com.kosilka.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wrapper around coroutine dispatchers to allow injection and test substitution.
 * All coroutine launches must use an explicit dispatcher from this class.
 */
@Singleton
class CoroutineDispatchers @Inject constructor() {
    val io: CoroutineDispatcher = Dispatchers.IO
    val default: CoroutineDispatcher = Dispatchers.Default
    val main: CoroutineDispatcher = Dispatchers.Main
}
