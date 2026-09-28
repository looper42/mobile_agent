package xyz.chouxuewei.mobile_agent.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Single publication point for overlay UI state; equal reductions do not invalidate Compose. */
internal class OverlayStateStore(initial: OverlayViewState = OverlayViewState()) {
    private val mutableState = MutableStateFlow(initial)
    val state: StateFlow<OverlayViewState> = mutableState.asStateFlow()

    fun publish(next: OverlayViewState) {
        if (mutableState.value != next) mutableState.value = next
    }
}
