package com.marketsboard.refresh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * "Is the app in the foreground?" as a gate for any background work (the quote refresh now, the news poller in M3).
 * The platform flips it (Android: Activity onResume calls [resume], onPause calls [pause]); work started with
 * [launchWhileActive] runs only while it is open, is cancelled on pause and started afresh on resume.
 */
class ActiveGate(initial: Boolean = false) {
    private val _isActive = MutableStateFlow(initial)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    fun resume() { _isActive.value = true }

    fun pause() { _isActive.value = false }

    /** Runs [block] while active; cancels it when the gate closes and restarts it when it opens again. */
    fun launchWhileActive(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit): Job =
        scope.launch { isActive.collectLatest { if (it) coroutineScope { block() } } }
}
