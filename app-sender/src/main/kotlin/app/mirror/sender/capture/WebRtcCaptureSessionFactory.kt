package app.mirror.sender.capture

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.core.content.getSystemService
import app.mirror.core.webrtc.CaptureConfig
import app.mirror.core.webrtc.ScreenCapture
import app.mirror.core.webrtc.ScreenCaptureListener
import app.mirror.core.webrtc.ScreenCaptureSession
import app.mirror.core.webrtc.SwitchableVideoSink
import app.mirror.core.webrtc.WebRtcRuntime
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class WebRtcCaptureSessionFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runtime: WebRtcRuntime,
) : CaptureSessionFactory {

    override fun create(
        permissionData: Intent,
        listener: ScreenCaptureListener,
        previewSink: SwitchableVideoSink,
    ): ScreenCapture = ScreenCaptureSession(
        context = context,
        runtime = runtime,
        permissionData = permissionData,
        config = currentConfig(),
        previewSink = previewSink,
        listener = listener,
    )

    /** Real display size in its current orientation. */
    @Suppress("DEPRECATION")
    private fun currentConfig(): CaptureConfig {
        val display = context.getSystemService<DisplayManager>()?.getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point().also { display?.getRealSize(it) }
        return if (size.x > 0 && size.y > 0) {
            CaptureConfig.forDisplay(size.x, size.y)
        } else {
            CaptureConfig.forDisplay(1080, 1920)
        }
    }
}
