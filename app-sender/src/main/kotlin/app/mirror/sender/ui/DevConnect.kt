package app.mirror.sender.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.mirror.sender.R
import app.mirror.sender.stream.StreamFailure
import app.mirror.sender.stream.StreamState

/**
 * TEMPORARY dev-only connect UI (shown in debug builds): server address + the code shown by the
 * receiver or the browser test page. Replaced by the QR / code pairing screen in a later step.
 */
@Composable
internal fun DevConnectCard(
    server: String,
    onServerChange: (String) -> Unit,
    code: String,
    onCodeChange: (String) -> Unit,
    error: DevInputError?,
    streamState: StreamState,
    canConnect: Boolean,
    onConnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dev_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.dev_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = server,
                onValueChange = onServerChange,
                label = { Text(stringResource(R.string.dev_server_label)) },
                singleLine = true,
                isError = error == DevInputError.SERVER_INVALID,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = code,
                onValueChange = onCodeChange,
                label = { Text(stringResource(R.string.dev_code_label)) },
                singleLine = true,
                isError = error == DevInputError.CODE_INVALID,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            devErrorText(error)?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Text(streamText(streamState), style = MaterialTheme.typography.bodyMedium)
            (streamState as? StreamState.Streaming)?.stats?.let { s ->
                Text(
                    stringResource(R.string.stream_stats, (s.codec ?: "?").removePrefix("video/"), s.width, s.height, s.fps, s.bitrateKbps),
                    style = MaterialTheme.typography.bodySmall,
                )
                s.encoder?.let {
                    Text(stringResource(R.string.stream_stats_encoder, it), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (canConnect) {
                Button(onClick = onConnect) { Text(stringResource(R.string.action_connect)) }
            }
        }
    }
}

@Composable
private fun devErrorText(error: DevInputError?): String? = when (error) {
    DevInputError.SERVER_INVALID -> stringResource(R.string.dev_server_invalid)
    DevInputError.CODE_INVALID -> stringResource(R.string.dev_code_invalid)
    DevInputError.FILL_BOTH -> stringResource(R.string.dev_fill_both)
    DevInputError.NOT_CAPTURING -> stringResource(R.string.dev_not_capturing)
    null -> null
}

@Composable
private fun streamText(state: StreamState): String = when (state) {
    StreamState.Idle -> stringResource(R.string.stream_idle)
    StreamState.Connecting -> stringResource(R.string.stream_connecting)
    StreamState.WaitingForApproval -> stringResource(R.string.stream_waiting_approval)
    StreamState.Negotiating -> stringResource(R.string.stream_negotiating)
    is StreamState.Streaming -> stringResource(R.string.stream_streaming)
    StreamState.Ended -> stringResource(R.string.stream_ended)
    is StreamState.Failed -> stringResource(
        when (state.reason) {
            StreamFailure.SERVER_UNREACHABLE -> R.string.stream_failed_server_unreachable
            StreamFailure.SIGNALING_LOST -> R.string.stream_failed_signaling_lost
            StreamFailure.CODE_REJECTED -> R.string.stream_failed_code_rejected
            StreamFailure.RATE_LIMITED -> R.string.stream_failed_rate_limited
            StreamFailure.DENIED_BY_RECEIVER -> R.string.stream_failed_denied
            StreamFailure.APPROVAL_TIMEOUT -> R.string.stream_failed_approval_timeout
            StreamFailure.RECEIVER_GONE -> R.string.stream_failed_receiver_gone
            StreamFailure.SIGNALING_ERROR -> R.string.stream_failed_signaling_error
            StreamFailure.CONNECTION_FAILED -> R.string.stream_failed_connection
            StreamFailure.INTERNAL -> R.string.stream_failed_internal
        },
    )
}
