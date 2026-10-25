package app.mirror.sender.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import app.mirror.core.webrtc.SwitchableVideoSink
import app.mirror.core.webrtc.WebRtcRuntime
import app.mirror.sender.capture.CaptureRepository
import app.mirror.sender.capture.CaptureState
import app.mirror.core.signaling.ServerAddress
import app.mirror.sender.stream.StreamController
import app.mirror.sender.stream.StreamState
import app.mirror.sender.stream.StreamTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.EglBase

/** One-shot, user-facing messages that are not part of the capture state. */
enum class UiMessage { CONSENT_DECLINED, NO_CAST_SETTINGS, START_FAILED }

/** Validation result for the dev connect fields. */
enum class DevInputError { SERVER_INVALID, CODE_INVALID, FILL_BOTH, NOT_CAPTURING }

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val repository: CaptureRepository,
    private val streamController: StreamController,
    private val runtime: WebRtcRuntime,
) : ViewModel() {

    private val prefs = context.getSharedPreferences("dev_connect", Context.MODE_PRIVATE)

    val captureState: StateFlow<CaptureState> = repository.state
    val streamState: StateFlow<StreamState> = streamController.state
    val previewSink: SwitchableVideoSink get() = repository.previewSink
    val eglBaseContext: EglBase.Context get() = runtime.eglBase.eglBaseContext

    /** Only the (non-secret) server address is remembered. The pairing code never is. */
    val lastServer: String = prefs.getString(KEY_SERVER, "").orEmpty()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    /**
     * Called when Start is tapped. With both fields empty the capture runs without streaming.
     * Otherwise the target is armed and connects as soon as the capture produces frames.
     * @return null when the input is acceptable.
     */
    fun onStartRequested(serverInput: String, codeInput: String): DevInputError? {
        // A previous Stopped/Failed banner is stale as soon as the user tries again.
        repository.resetToIdle()
        if (serverInput.isBlank() && codeInput.isBlank()) {
            streamController.setPendingTarget(null)
            return null
        }
        val target = parseTarget(serverInput, codeInput)
        if (target is Parsed.Error) return target.error
        streamController.setPendingTarget((target as Parsed.Ok).target)
        return null
    }

    /** Connect (or reconnect with a new code) on the capture that is already running. */
    fun onConnectRequested(serverInput: String, codeInput: String): DevInputError? {
        val parsed = parseTarget(serverInput, codeInput)
        if (parsed is Parsed.Error) return parsed.error
        return if (streamController.connect((parsed as Parsed.Ok).target)) null else DevInputError.NOT_CAPTURING
    }

    private sealed interface Parsed {
        data class Ok(val target: StreamTarget) : Parsed
        data class Error(val error: DevInputError) : Parsed
    }

    private fun parseTarget(serverInput: String, codeInput: String): Parsed {
        if (serverInput.isBlank() || codeInput.isBlank()) return Parsed.Error(DevInputError.FILL_BOTH)
        val url = ServerAddress.normalize(serverInput) ?: return Parsed.Error(DevInputError.SERVER_INVALID)
        val code = codeInput.trim()
        if (!ServerAddress.isValidCode(code)) return Parsed.Error(DevInputError.CODE_INVALID)
        prefs.edit().putString(KEY_SERVER, serverInput.trim()).apply()
        return Parsed.Ok(StreamTarget(url, code))
    }

    fun onConsentDeclined() {
        streamController.setPendingTarget(null) // do not keep an armed code around
        _message.value = UiMessage.CONSENT_DECLINED
    }

    fun onStartFailed() {
        streamController.setPendingTarget(null)
        _message.value = UiMessage.START_FAILED
    }

    fun onNoCastSettings() {
        _message.value = UiMessage.NO_CAST_SETTINGS
    }

    fun onMessageShown() {
        _message.value = null
    }

    fun onDismissStatus() = repository.resetToIdle()

    private companion object {
        const val KEY_SERVER = "server"
    }
}
