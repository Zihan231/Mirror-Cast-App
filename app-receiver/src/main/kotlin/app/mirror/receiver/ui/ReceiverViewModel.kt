package app.mirror.receiver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mirror.core.signaling.ServerAddress
import app.mirror.core.webrtc.SwitchableVideoSink
import app.mirror.core.webrtc.WebRtcRuntime
import app.mirror.receiver.ServerStore
import app.mirror.receiver.discovery.DiscoveredServer
import app.mirror.receiver.discovery.NsdServerDiscovery
import app.mirror.receiver.session.ReceiverController
import app.mirror.receiver.session.ReceiverNotice
import app.mirror.receiver.session.ReceiverState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.EglBase

@HiltViewModel
class ReceiverViewModel @Inject constructor(
    private val controller: ReceiverController,
    private val discovery: NsdServerDiscovery,
    private val store: ServerStore,
    private val runtime: WebRtcRuntime,
) : ViewModel() {

    val state: StateFlow<ReceiverState> = controller.state
    val notice: StateFlow<ReceiverNotice?> = controller.notice
    val discovered: StateFlow<List<DiscoveredServer>> = discovery.servers
    val videoSink: SwitchableVideoSink get() = controller.videoSink
    val eglBaseContext: EglBase.Context get() = runtime.eglBase.eglBaseContext

    val lastServer: String? get() = store.lastServer

    private val _debugOverlay = MutableStateFlow(false)
    val debugOverlay: StateFlow<Boolean> = _debugOverlay.asStateFlow()

    private val _inputInvalid = MutableStateFlow(false)
    val inputInvalid: StateFlow<Boolean> = _inputInvalid.asStateFlow()

    /** Reconnect to the last server on launch until the user explicitly chooses to change it. */
    private var autoConnect = true

    init {
        viewModelScope.launch {
            controller.state.collect { s ->
                // A code was issued, so the address works: remember it.
                if (s is ReceiverState.Pairing) store.lastServer = s.serverUrl
            }
        }
    }

    fun onForeground() {
        discovery.start()
        if (autoConnect && controller.state.value is ReceiverState.Idle) store.lastServer?.let(controller::start)
    }

    /** Home pressed or another app took over: end the session and release the network. */
    fun onBackgrounded() {
        discovery.stop()
        controller.stop()
    }

    fun connect(input: String): Boolean {
        val url = ServerAddress.normalize(input)
        _inputInvalid.value = url == null
        if (url == null) return false
        autoConnect = true
        controller.start(url)
        return true
    }

    fun connect(server: DiscoveredServer) {
        _inputInvalid.value = false
        autoConnect = true
        controller.start(server.url)
    }

    fun changeServer() {
        autoConnect = false
        controller.stop()
    }

    fun approve() = controller.approve()
    fun reject() = controller.reject()
    fun dismissNotice() = controller.dismissNotice()
    fun toggleDebugOverlay() {
        _debugOverlay.value = !_debugOverlay.value
    }

    /**
     * Back button. @return true if consumed; false lets the activity finish.
     * Streaming/negotiating -> end the session; approval -> reject; anything else leaves the app.
     */
    fun onBack(): Boolean = when (controller.state.value) {
        is ReceiverState.Streaming, is ReceiverState.Negotiating -> {
            controller.endSession()
            true
        }
        is ReceiverState.AwaitingApproval -> {
            controller.reject()
            true
        }
        is ReceiverState.Connecting, is ReceiverState.Failed -> {
            changeServer()
            true
        }
        is ReceiverState.Pairing, ReceiverState.Idle -> false
    }

    override fun onCleared() {
        controller.stop()
        discovery.stop()
    }
}
