package app.mirror.receiver.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mirror.receiver.session.ReceiverState

@Composable
fun ReceiverRoot(onExit: () -> Unit, viewModel: ReceiverViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val discovered by viewModel.discovered.collectAsStateWithLifecycle()
    val inputInvalid by viewModel.inputInvalid.collectAsStateWithLifecycle()
    val debug by viewModel.debugOverlay.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onForeground() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackgrounded() }

    BackHandler { if (!viewModel.onBack()) onExit() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val s = state) {
            ReceiverState.Idle -> ServerScreen(
                discovered = discovered,
                lastServer = viewModel.lastServer,
                inputInvalid = inputInvalid,
                onConnectDiscovered = viewModel::connect,
                onConnectManual = { viewModel.connect(it) },
                onUseLast = { viewModel.lastServer?.let { last -> viewModel.connect(last) } },
            )
            is ReceiverState.Connecting -> ConnectingScreen(s, onChangeServer = viewModel::changeServer)
            is ReceiverState.Pairing -> PairingScreen(s, notice, onChangeServer = viewModel::changeServer)
            is ReceiverState.AwaitingApproval -> ApprovalScreen(s, onApprove = viewModel::approve, onReject = viewModel::reject)
            is ReceiverState.Negotiating -> StreamScreen(s.peerName, null, debug, viewModel.videoSink, viewModel.eglBaseContext)
            is ReceiverState.Streaming -> StreamScreen(s.peerName, s.stats, debug, viewModel.videoSink, viewModel.eglBaseContext)
            is ReceiverState.Failed -> FailedScreen(s, onChangeServer = viewModel::changeServer)
        }
    }
}
