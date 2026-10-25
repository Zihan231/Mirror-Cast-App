package app.mirror.sender.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.mirror.core.webrtc.SwitchableVideoSink
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Local preview of the captured frames. Attaches to the long-lived [sink], never to the video
 * track directly, so a track that is disposed by the capture session cannot crash the UI.
 */
@Composable
fun CapturePreview(sink: SwitchableVideoSink, eglContext: EglBase.Context, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(eglContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(true)
                sink.target = this
            }
        },
        onRelease = { renderer ->
            // A newer renderer may already have taken over (configuration change).
            if (sink.target === renderer) sink.target = null
            renderer.release()
        },
    )
}
