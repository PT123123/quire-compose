// 相机参数全部走 Camera1（android.hardware.Camera）：zxing-android-embedded 4.3.0
// 本身就是基于它实现的，没有 CameraX 的路可走，统一压掉废弃告警。
@file:Suppress("DEPRECATION")

package dev.quire.compose

import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.Camera
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.camera.CameraInstance
import com.journeyapps.barcodescanner.camera.CameraManager

/**
 * 扫码页: the capture screen for the 同步 page's 「扫码接收」.
 *
 * Ported from ActivityWatch's `AwCaptureActivity` rather than written fresh,
 * because the problem it solves is a library default and not a preference:
 * `CameraSettings.focusMode = AUTO` is a *one-shot* focus, and the library's
 * `AutoFocusManager` re-issues `autoFocus()` only while its `focusing` flag is
 * set — a flag only `onAutoFocus` clears. One dropped callback (common on modern
 * ROMs driving the legacy Camera1) and the flag latches forever: the lens stops
 * moving and the preview stays soft. So the default becomes CONTINUOUS, which the
 * HAL keeps in focus without the callback chain, and a tap-to-focus is layered on
 * top for the user who aims at the wrong part of the screen.
 *
 * The rest of the port is the three details that come with it, unchanged and for
 * the same reasons:
 *
 * * the settings are changed **after** `super.onCreate()`, because
 *   `initializeContent()` builds and binds the `CameraSettings` inside the
 *   parent's `onCreate` and anything set before it is overwritten;
 * * a tap focus switches to AUTO, sets the area, calls `autoFocus()`, and
 *   switches back to CONTINUOUS on the callback **or** on a timer — Camera1
 *   ignores `setFocusAreas()` unless the mode is AUTO, so the temporary switch is
 *   the only way the area is honoured at all;
 * * the `CameraManager` behind `BarcodeView` is reached by reflection, because
 *   `CameraInstance.getCameraManager()` is protected. Release builds here do not
 *   minify, so no method name is rewritten under it.
 */
class QrCaptureActivity : CaptureActivity() {

    private companion object {
        const val TAG = "QrCapture"

        /**
         * focus area 半边长. The coordinate space is (-1000,-1000)..(1000,1000),
         * so 250 is a square covering a quarter of the frame: big enough to
         * absorb the rounding below, small enough that a HAL will not ignore it.
         */
        const val FOCUS_AREA_HALF = 250f

        /** Back to continuous if the `autoFocus` callback never arrives. */
        const val RESTORE_DELAY_MS = 2500L
    }

    private var decoratedBarcodeView: DecoratedBarcodeView? = null

    /** Lives as long as the preview does; its inner camera follows the switch. */
    private var cachedCameraManager: CameraManager? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun initializeContent(): DecoratedBarcodeView {
        val view = super.initializeContent()
        decoratedBarcodeView = view
        return view
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = decoratedBarcodeView ?: return

        view.cameraSettings?.isContinuousFocusEnabled = true

        // A tap focuses where the finger went; the up event still performs a click
        // so accessibility sees the gesture it expects.
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    focusAt(v, event.x, event.y)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.performClick()
                    true
                }
                else -> true
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ==================== 点按对焦 ====================

    private fun focusAt(view: View, x: Float, y: Float) {
        val camera = currentCamera() ?: return
        val preview = decoratedBarcodeView?.barcodeView ?: return
        try {
            val params = camera.parameters ?: return
            val modes = params.supportedFocusModes ?: emptyList()

            // No controllable focus at all: one trigger, no area to set.
            if (!modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                camera.autoFocus(null)
                return
            }

            params.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
            focusArea(view, preview, x, y)?.let { area ->
                if (params.maxNumFocusAreas > 0) {
                    params.focusAreas = listOf(Camera.Area(area, 1000))
                }
                if (params.maxNumMeteringAreas > 0) {
                    params.meteringAreas = listOf(Camera.Area(area, 800))
                }
            }
            camera.parameters = params

            camera.autoFocus { _, _ -> mainHandler.post { restoreContinuous(camera) } }
            mainHandler.postDelayed({ restoreContinuous(camera) }, RESTORE_DELAY_MS)

            showFocusIndicator(view, x, y)
        } catch (t: Throwable) {
            Log.w(TAG, "点按对焦失败", t)
        }
    }

    private fun restoreContinuous(camera: Camera) {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            val params = camera.parameters ?: return
            val modes = params.supportedFocusModes ?: emptyList()
            when {
                modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) ->
                    params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) ->
                    params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            }
            params.focusAreas = emptyList<Camera.Area>()
            params.meteringAreas = emptyList<Camera.Area>()
            camera.parameters = params
        } catch (t: Throwable) {
            Log.w(TAG, "切回连续对焦失败", t)
        }
    }

    /**
     * View coordinates → `Camera.Area` sensor coordinates (-1000..1000).
     *
     * Two steps: the point in the rotated preview (which fills the view
     * centre-cropped), then that point rotated back into the sensor's own frame.
     */
    private fun focusArea(view: View, preview: BarcodeView, x: Float, y: Float): Rect? {
        val natural = preview.previewSize ?: return null
        val rotation = preview.cameraInstance?.cameraRotation ?: 0
        val viewWidth = view.width.toFloat()
        val viewHeight = view.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return null

        // The natural size is in sensor orientation; at 90/270 the display swaps it.
        val rotatedWidth: Float
        val rotatedHeight: Float
        if (rotation == 90 || rotation == 270) {
            rotatedWidth = natural.height.toFloat()
            rotatedHeight = natural.width.toFloat()
        } else {
            rotatedWidth = natural.width.toFloat()
            rotatedHeight = natural.height.toFloat()
        }

        // The same centre-crop the library's own CenterCropStrategy performs.
        val scale = maxOf(viewWidth / rotatedWidth, viewHeight / rotatedHeight)
        val offsetX = (viewWidth - rotatedWidth * scale) / 2f
        val offsetY = (viewHeight - rotatedHeight * scale) / 2f

        val nx = ((x - offsetX) / scale / rotatedWidth) * 2000f - 1000f
        val ny = ((y - offsetY) / scale / rotatedHeight) * 2000f - 1000f

        val sensorX: Float
        val sensorY: Float
        when (rotation) {
            90 -> {
                sensorX = ny
                sensorY = -nx
            }
            180 -> {
                sensorX = -nx
                sensorY = -ny
            }
            270 -> {
                sensorX = -ny
                sensorY = nx
            }
            else -> {
                sensorX = nx
                sensorY = ny
            }
        }

        val half = FOCUS_AREA_HALF
        val left = (sensorX - half).coerceIn(-1000f, 1000f).toInt()
        val top = (sensorY - half).coerceIn(-1000f, 1000f).toInt()
        val right = (sensorX + half).coerceIn(-1000f, 1000f).toInt()
        val bottom = (sensorY + half).coerceIn(-1000f, 1000f).toInt()
        if (right - left < 10 || bottom - top < 10) return null

        Log.d(TAG, "点按 ($x, $y) rot=$rotation -> area [$left,$top,$right,$bottom]")
        return Rect(left, top, right, bottom)
    }

    /** A ring at the tapped point, so the tap is visibly acknowledged. */
    private fun showFocusIndicator(view: View, x: Float, y: Float) {
        val parent = view as? FrameLayout ?: return
        val density = resources.displayMetrics.density
        val size = (72 * density).toInt()
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x22FFFFFF)
            setStroke((2 * density).toInt(), 0xCCFFFFFF.toInt())
        }
        val dot = View(parent.context).apply {
            background = ring
            isClickable = false
            isFocusable = false
        }
        // The overlay does not consume touches, so the preview underneath and the
        // listener above keep receiving them.
        parent.addView(
            dot,
            FrameLayout.LayoutParams(size, size).apply {
                leftMargin = (x - size / 2f).toInt()
                topMargin = (y - size / 2f).toInt()
            }
        )
        dot.animate()
            .alpha(0f)
            .setStartDelay(280)
            .setDuration(420)
            .withEndAction { parent.removeView(dot) }
            .start()
    }

    // ==================== Camera ====================

    private fun currentCamera(): Camera? {
        val preview = decoratedBarcodeView?.barcodeView ?: return null
        val instance = preview.cameraInstance ?: return null
        var manager = cachedCameraManager
        if (manager == null) {
            manager = resolveCameraManager(instance) ?: return null
            cachedCameraManager = manager
        }
        return try {
            manager.camera
        } catch (t: Throwable) {
            null
        }
    }

    private fun resolveCameraManager(instance: CameraInstance): CameraManager? = try {
        CameraInstance::class.java
            .getDeclaredMethod("getCameraManager")
            .apply { isAccessible = true }
            .invoke(instance) as? CameraManager
    } catch (t: Throwable) {
        Log.w(TAG, "反射获取 CameraManager 失败", t)
        null
    }
}
