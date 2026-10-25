package app.mirror.sender.ui

import androidx.lifecycle.ViewModel
import app.mirror.core.webrtc.SwitchableVideoSink
import app.mirror.core.webrtc.WebRtcRuntime
import app.mirror.sender.capture.CaptureRepository
import app.mirror.sender.capture.CaptureState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.EglBase

/** One-shot, user-facing messages that are not part of the capture state. */
enum class UiMessage { CONSENT_DECLINED, NO_CAST_SETTINGS, START_FAILED }

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: CaptureRepository,
    private val runtime: WebRtcRuntime,
) : ViewModel() {

    val captureState: StateFlow<CaptureState> = repository.state
    val previewSink: SwitchableVideoSink get() = repository.previewSink
    val eglBaseContext: EglBase.Context get() = runtime.eglBase.eglBaseContext

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    fun onStartRequested() {
        // A previous Stopped/Failed banner is stale as soon as the user tries again.
        repository.resetToIdle()
    }

    fun onConsentDeclined() {
        _message.value = UiMessage.CONSENT_DECLINED
    }

    fun onStartFailed() {
        _message.value = UiMessage.START_FAILED
    }

    fun onNoCastSettings() {
        _message.value = UiMessage.NO_CAST_SETTINGS
    }

    fun onMessageShown() {
        _message.value = null
    }

    fun onDismissStatus() = repository.resetToIdle()
}
