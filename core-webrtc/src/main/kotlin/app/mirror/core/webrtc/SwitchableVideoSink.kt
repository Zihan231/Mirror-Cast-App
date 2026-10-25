package app.mirror.core.webrtc

import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * A sink that stays attached to the video track for the whole capture session while the UI
 * attaches and detaches renderers (rotation, backgrounding). The UI therefore never touches the
 * track itself, which may be disposed at any time by the capture session.
 */
class SwitchableVideoSink : VideoSink {
    @Volatile
    var target: VideoSink? = null

    override fun onFrame(frame: VideoFrame) {
        target?.onFrame(frame)
    }
}
