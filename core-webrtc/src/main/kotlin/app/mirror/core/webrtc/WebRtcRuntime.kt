package app.mirror.core.webrtc

import android.content.Context
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory

/**
 * Process-wide WebRTC state: native init, the shared EGL context and the factory.
 * Create exactly one per process (the apps provide it as a singleton).
 */
class WebRtcRuntime(context: Context) {
    private val appContext = context.applicationContext

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions(),
        )
    }

    /** Shared by capture, encoder/decoder and every renderer so textures never cross contexts. */
    val eglBase: EglBase by lazy { EglBase.create() }

    val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }
}
