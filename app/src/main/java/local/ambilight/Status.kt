package local.ambilight

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

/** What the screen shows; written by the service threads. */
object Status {
    data class State(
        val adb: String = "—",
        val picture: String = "—",
        val strip: String = "—",
        val mode: String = "—",
        val problem: String = "",
        val segments: List<Int> = List(SEGMENTS) { 0 },
    )

    val state = MutableStateFlow(State())

    /** Updates the state and logs text changes (adb logcat -s Ambilight) so problems can be read without the screen. */
    fun set(change: (State) -> State) = synchronized(this) {
        val old = state.value
        val new = change(old)
        state.value = new
        if (new.copy(segments = old.segments) != old) {
            Log.i(TAG, "mode=${new.mode} | picture=${new.picture} | strip=${new.strip} | adb=${new.adb} | problem=${new.problem}")
        }
    }
}
