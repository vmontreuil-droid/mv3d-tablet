package be.mv3d.tablet

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Bediening op afstand — injecteert tikken/vegen die het portaal doorstuurt.
 * Coördinaten komen genormaliseerd binnen (0..1) zodat ze los staan van de
 * (geschaalde) MJPEG-resolutie; hier vermenigvuldigen we met de échte
 * schermgrootte want dispatchGesture werkt in werkelijke display-pixels.
 *
 * De machinist zet deze dienst één keer aan bij Instellingen ▸ Toegankelijkheid.
 * Er is geen systeem-app of root nodig.
 */
class RemoteInputService : AccessibilityService() {

    companion object {
        @Volatile var instance: RemoteInputService? = null
        val enabled: Boolean get() = instance != null
    }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    private fun realSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    /** Enkele tik op genormaliseerde coördinaat (0..1). */
    fun tap(nx: Float, ny: Float) {
        val (w, h) = realSize()
        val x = (nx.coerceIn(0f, 1f) * w)
        val y = (ny.coerceIn(0f, 1f) * h)
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** Veeg/sleep tussen twee genormaliseerde punten. */
    fun swipe(nx1: Float, ny1: Float, nx2: Float, ny2: Float, durationMs: Long) {
        val (w, h) = realSize()
        val path = Path().apply {
            moveTo(nx1.coerceIn(0f, 1f) * w, ny1.coerceIn(0f, 1f) * h)
            lineTo(nx2.coerceIn(0f, 1f) * w, ny2.coerceIn(0f, 1f) * h)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(20, 3000))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** Systeem-acties zonder coördinaten (knoppen in de portaal-werkbalk). */
    fun globalBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun globalHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun globalRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
}
