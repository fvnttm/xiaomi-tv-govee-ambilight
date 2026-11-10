package local.ambilight

/**
 * While the app is open, the TV shows the app itself, so the strip reads only the demo video on its screen:
 * [region] is that video's rectangle in panel pixels (x, y, width, height), or null for the whole picture.
 * The screen sets it; the running session passes it on to the daemon.
 */
object Demo {
    @Volatile var region: IntArray? = null
        private set

    /** Set by the running session; called on the thread that changed the region. */
    @Volatile var listener: ((IntArray?) -> Unit)? = null

    private var wanted: IntArray? = null
    private var appVisible = true

    /** Where the video is on screen; null when it is not shown. */
    @Synchronized
    fun set(r: IntArray?) {
        wanted = r
        publish()
    }

    /** The app went to the background (the picture is the TV's again) or came back. */
    @Synchronized
    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        publish()
    }

    private fun publish() {
        val r = if (appVisible) wanted else null
        if (r.contentEquals(region)) return
        region = r
        listener?.invoke(r)
    }
}
