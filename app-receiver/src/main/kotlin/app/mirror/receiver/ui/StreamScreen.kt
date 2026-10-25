@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.mirror.receiver.ui

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.mirror.core.webrtc.ReceiverStats
import app.mirror.core.webrtc.SwitchableVideoSink
import app.mirror.receiver.R
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Fullscreen video. The picture is letterboxed (SCALE_ASPECT_FIT), so a portrait phone on a
 * landscape TV gets black side bars and a rotating sender simply re-fits when the frame size changes.
 * Frames come through [sink]; the renderer is detached before it is released.
 */
@Composable
fun StreamScreen(
    peerName: String,
    stats: ReceiverStats?,
    showDebug: Boolean,
    sink: SwitchableVideoSink,
    eglContext: EglBase.Context,
) {
    var firstFrame by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val renderer = SurfaceViewRenderer(context)
                FrameLayout(context).apply {
                    renderer.init(
                        eglContext,
                        object : RendererCommon.RendererEvents {
                            override fun onFirstFrameRendered() {
                                post { firstFrame = true }
                            }

                            override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) = Unit
                        },
                    )
                    renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    renderer.setEnableHardwareScaler(true)
                    renderer.setMirror(false)
                    addView(renderer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
                    tag = renderer
                    sink.target = renderer
                }
            },
            onRelease = { view ->
                val renderer = view.tag as SurfaceViewRenderer
                if (sink.target === renderer) sink.target = null
                renderer.release()
            },
        )

        if (!firstFrame) {
            Text(
                stringResource(R.string.stream_starting, peerName),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (showDebug) {
            DebugOverlay(stats, Modifier.align(Alignment.TopStart).padding(32.dp))
        }
    }
}
