@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.mirror.receiver.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.mirror.core.webrtc.ReceiverStats
import app.mirror.receiver.R
import app.mirror.receiver.discovery.DiscoveredServer
import app.mirror.receiver.session.ReceiverFailure
import app.mirror.receiver.session.ReceiverNotice
import app.mirror.receiver.session.ReceiverState
import kotlinx.coroutines.delay

private val Margin = 64.dp

/** Requests focus once when the screen appears, so D-pad input works without a touch. */
@Composable
private fun rememberInitialFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50) // the focus target must be attached before it can take focus
        runCatching { requester.requestFocus() }
    }
    return requester
}

/** Seconds left until [deadlineElapsedMs], ticking once a second. */
@Composable
private fun rememberSecondsLeft(deadlineElapsedMs: Long): Int {
    var left by remember(deadlineElapsedMs) { mutableStateOf(secondsUntil(deadlineElapsedMs)) }
    LaunchedEffect(deadlineElapsedMs) {
        while (true) {
            left = secondsUntil(deadlineElapsedMs)
            delay(500)
        }
    }
    return left
}

private fun secondsUntil(deadline: Long): Int = ((deadline - SystemClock.elapsedRealtime() + 999) / 1000).toInt().coerceAtLeast(0)

// ---------------------------------------------------------------------------------------------

@Composable
fun ServerScreen(
    discovered: List<DiscoveredServer>,
    lastServer: String?,
    inputInvalid: Boolean,
    onConnectDiscovered: (DiscoveredServer) -> Unit,
    onConnectManual: (String) -> Unit,
    onUseLast: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val firstFocus = rememberInitialFocus()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Margin, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.server_title), style = MaterialTheme.typography.displaySmall)

        Text(stringResource(R.string.server_found), style = MaterialTheme.typography.titleMedium)
        if (discovered.isEmpty()) {
            Text(
                stringResource(R.string.server_searching) + "  " + stringResource(R.string.server_none_found),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        discovered.forEachIndexed { index, server ->
            Button(
                onClick = { onConnectDiscovered(server) },
                modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
            ) { Text(server.label) }
        }

        lastServer?.let {
            Button(onClick = onUseLast, modifier = if (discovered.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier) {
                Text(stringResource(R.string.server_use_last, it))
            }
        }

        Text(stringResource(R.string.server_manual), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AddressField(
                value = text,
                onValueChange = { text = it },
                isError = inputInvalid,
                onGo = { onConnectManual(text) },
                modifier = Modifier.widthIn(min = 420.dp),
            )
            Button(onClick = { onConnectManual(text) }) { Text(stringResource(R.string.action_connect)) }
        }
        if (inputInvalid) {
            Text(stringResource(R.string.server_invalid), color = MaterialTheme.colorScheme.error)
        }
    }
}

/** A text field that a D-pad can focus and open: OK on the remote shows the on-screen keyboard. */
@Composable
private fun AddressField(
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
    onGo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val borderColor = when {
        isError -> MaterialTheme.colorScheme.error
        focused -> MaterialTheme.colorScheme.primary
        else -> Color(0xFF3B4A5C)
    }
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.filterNot(Char::isWhitespace).take(200)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                val select = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (select && e.type == KeyEventType.KeyUp) {
                    keyboard?.show()
                    true
                } else {
                    false
                }
            },
        decorationBox = { inner ->
            Box(
                Modifier
                    .border(if (focused) 3.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) {
                    Text(stringResource(R.string.server_hint), style = MaterialTheme.typography.titleLarge, color = Color(0xFF6B7A8C), fontFamily = FontFamily.Monospace)
                }
                inner()
            }
        },
    )
}

@Composable
fun ConnectingScreen(state: ReceiverState.Connecting, onChangeServer: () -> Unit) {
    val focus = rememberInitialFocus()
    CenteredColumn {
        Text(
            if (state.attempt > 0) stringResource(R.string.reconnecting_to, state.serverUrl) else stringResource(R.string.connecting_to, state.serverUrl),
            style = MaterialTheme.typography.headlineMedium,
        )
        Button(onClick = onChangeServer, modifier = Modifier.focusRequester(focus)) { Text(stringResource(R.string.action_change_server)) }
    }
}

@Composable
fun PairingScreen(state: ReceiverState.Pairing, notice: ReceiverNotice?, onChangeServer: () -> Unit) {
    val focus = rememberInitialFocus()
    val left = rememberSecondsLeft(state.expiresAtElapsedMs)
    CenteredColumn {
        notice?.let {
            Text(noticeText(it), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        }
        Text(stringResource(R.string.pairing_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            state.code.chunked(3).joinToString("  "),
            fontSize = 120.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            if (left > 0) stringResource(R.string.pairing_expires, left) else stringResource(R.string.pairing_refreshing),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(stringResource(R.string.pairing_server, state.serverUrl), style = MaterialTheme.typography.bodyMedium, color = Color(0xFF9FB1C5))
        Button(onClick = onChangeServer, modifier = Modifier.focusRequester(focus)) { Text(stringResource(R.string.action_change_server)) }
    }
}

@Composable
private fun noticeText(notice: ReceiverNotice): String = stringResource(
    when (notice) {
        ReceiverNotice.SESSION_ENDED -> R.string.notice_session_ended
        ReceiverNotice.APPROVAL_TIMED_OUT -> R.string.notice_approval_timeout
        ReceiverNotice.CONNECTION_FAILED -> R.string.notice_connection_failed
    },
)

/** Explicit approval. Focus starts on Reject so a stray OK press never admits a stranger. */
@Composable
fun ApprovalScreen(state: ReceiverState.AwaitingApproval, onApprove: () -> Unit, onReject: () -> Unit) {
    val rejectFocus = rememberInitialFocus()
    val left = rememberSecondsLeft(state.deadlineElapsedMs)
    Box(Modifier.fillMaxSize().background(Color(0xF00B0F14)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(R.string.approval_title, state.senderName), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.approval_hint), style = MaterialTheme.typography.bodyLarge, color = Color(0xFF9FB1C5))
            Text(stringResource(R.string.approval_countdown, left), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Button(onClick = onReject, modifier = Modifier.focusRequester(rejectFocus)) { Text(stringResource(R.string.action_reject)) }
                Button(onClick = onApprove) { Text(stringResource(R.string.action_approve)) }
            }
        }
    }
}

@Composable
fun FailedScreen(state: ReceiverState.Failed, onChangeServer: () -> Unit) {
    val focus = rememberInitialFocus()
    CenteredColumn {
        Text(stringResource(R.string.failed_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            when (state.reason) {
                ReceiverFailure.SERVER_ERROR -> stringResource(R.string.failed_server_error, state.detail.orEmpty())
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onChangeServer, modifier = Modifier.focusRequester(focus)) { Text(stringResource(R.string.action_change_server)) }
    }
}

@Composable
private fun CenteredColumn(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(Margin), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) { content() }
    }
}

// ---------------------------------------------------------------------------------------------

/** Debug overlay: what the receiver measures. Toggled with MENU / INFO. */
@Composable
fun DebugOverlay(stats: ReceiverStats?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Color(0xB3000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .fillMaxWidth(0.5f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val mono = FontFamily.Monospace
        Text(stringResource(R.string.debug_title), fontFamily = mono, fontSize = 16.sp, color = Color(0xFF7FD6CB))
        if (stats == null) {
            Text(stringResource(R.string.debug_waiting), fontFamily = mono, fontSize = 16.sp)
        } else {
            Text(
                stringResource(R.string.debug_latency, stats.estimatedLatencyMs, (stats.rttMs ?: 0) / 2, stats.jitterBufferMs, stats.decodeMs),
                fontFamily = mono,
                fontSize = 16.sp,
            )
            Text(stringResource(R.string.debug_video, stats.width, stats.height, stats.fps), fontFamily = mono, fontSize = 16.sp)
            Text(stringResource(R.string.debug_bitrate, stats.bitrateKbps, stats.packetsLost), fontFamily = mono, fontSize = 16.sp)
            Text(
                stringResource(R.string.debug_codec, (stats.codec ?: "?").removePrefix("video/"), stats.decoder ?: "?"),
                fontFamily = mono,
                fontSize = 16.sp,
            )
        }
    }
}
