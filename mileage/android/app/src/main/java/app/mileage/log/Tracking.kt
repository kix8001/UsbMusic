package app.mileage.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 計測中の状態。TrackingService が書き、画面が読む */
object Tracking {
    data class State(
        val running: Boolean = false,
        val startTime: Long = 0L,
        val meters: Double = 0.0,
        val speedKmh: Float = 0f,
        val accuracy: Float? = null,
        val hasFix: Boolean = false,
    )

    internal val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable
}
