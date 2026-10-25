package app.mirror.sender.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mirror.sender.BuildConfig
import app.mirror.sender.R
import app.mirror.sender.capture.CaptureService
import app.mirror.sender.capture.CaptureState
import app.mirror.sender.capture.FailureReason
import app.mirror.sender.capture.StopReason
import app.mirror.sender.capture.isActive
import app.mirror.sender.stream.StreamFailure
import app.mirror.sender.stream.StreamState
import app.mirror.sender.stream.isBusy

@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.captureState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val streamState by viewModel.streamState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // Dev connect fields. The code is single-use and is cleared as soon as it has been handed over.
    var server by rememberSaveable { mutableStateOf(viewModel.lastServer) }
    var code by rememberSaveable { mutableStateOf("") }
    var devError by remember { mutableStateOf<DevInputError?>(null) }

    // Re-checked on resume: the user may flip the setting in system settings and come back.
    var notificationsEnabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    // Consent is requested for every session; the result is handed straight to the service and never kept.
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            try {
                ContextCompat.startForegroundService(context, CaptureService.startIntent(context, result.resultCode, data))
            } catch (e: Exception) {
                viewModel.onStartFailed()
            }
        } else {
            viewModel.onConsentDeclined()
        }
    }
    fun launchConsent() {
        // The no-argument intent on Android 14+ offers "A single app" and "Entire screen".
        // Do NOT switch to MediaProjectionConfig.createConfigForDefaultDisplay(): that forces full screen.
        val manager = context.getSystemService<MediaProjectionManager>()
        if (manager == null) viewModel.onStartFailed() else consentLauncher.launch(manager.createScreenCaptureIntent())
    }

    // Denial is not fatal: the capture still works, we only warn that the Stop control is hidden.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        launchConsent()
    }

    fun onStart() {
        val error = viewModel.onStartRequested(server, code)
        devError = error
        if (error != null) return
        code = ""
        val needsRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsRuntimePermission) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else launchConsent()
    }

    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        snackbar.showSnackbar(
            context.getString(
                when (m) {
                    UiMessage.CONSENT_DECLINED -> R.string.message_consent_declined
                    UiMessage.NO_CAST_SETTINGS -> R.string.message_no_cast_settings
                    UiMessage.START_FAILED -> R.string.message_start_failed
                },
            ),
        )
        viewModel.onMessageShown()
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)

            StatusCard(state = state, onDismiss = viewModel::onDismissStatus)

            if (BuildConfig.DEBUG) {
                DevConnectCard(
                    server = server,
                    onServerChange = { server = it },
                    code = code,
                    onCodeChange = { code = it.filter(Char::isDigit).take(6) },
                    error = devError,
                    streamState = streamState,
                    canConnect = state is CaptureState.Capturing && !streamState.isBusy,
                    onConnect = {
                        val error = viewModel.onConnectRequested(server, code)
                        devError = error
                        if (error == null) code = ""
                    },
                )
            }

            if (!notificationsEnabled) {
                NotificationsOffCard(onOpenSettings = { openNotificationSettings(context) })
            }

            if (state is CaptureState.Capturing) {
                Text(stringResource(R.string.preview_hint), style = MaterialTheme.typography.bodySmall)
                CapturePreview(
                    sink = viewModel.previewSink,
                    eglContext = viewModel.eglBaseContext,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                )
            } else {
                Column(Modifier.weight(1f)) {}
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.isActive) {
                    Button(
                        onClick = { context.startService(CaptureService.stopIntent(context)) },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.action_stop)) }
                } else {
                    Button(onClick = ::onStart, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.action_start))
                    }
                }
                OutlinedButton(
                    onClick = { if (!openCastSettings(context)) viewModel.onNoCastSettings() },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_quick_cast)) }
            }
            Text(stringResource(R.string.quick_cast_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusCard(state: CaptureState, onDismiss: () -> Unit) {
    val isProblem = state is CaptureState.Failed
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isProblem) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(statusText(state), style = MaterialTheme.typography.bodyLarge)
            if (state is CaptureState.Capturing) {
                if (state.sharedContentWidth != null && state.sharedContentHeight != null) {
                    Text(
                        stringResource(R.string.status_shared_content, state.sharedContentWidth, state.sharedContentHeight),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (!state.contentVisible) {
                    Text(
                        stringResource(R.string.status_content_hidden),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (state is CaptureState.Stopped || state is CaptureState.Failed) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
            }
        }
    }
}

@Composable
private fun statusText(state: CaptureState): String = when (state) {
    CaptureState.Idle -> stringResource(R.string.status_idle)
    CaptureState.Starting -> stringResource(R.string.status_starting)
    is CaptureState.Capturing -> stringResource(R.string.status_capturing, state.width, state.height, state.fps)
    is CaptureState.Stopped -> when (state.reason) {
        StopReason.USER_REQUESTED -> stringResource(R.string.stopped_user)
        StopReason.REMOTE_ENDED -> stringResource(R.string.stopped_remote)
        StopReason.SYSTEM -> stringResource(R.string.stopped_system)
    }
    is CaptureState.Failed -> when (state.reason) {
        FailureReason.PROJECTION_REJECTED -> stringResource(R.string.failed_projection_rejected)
        FailureReason.FOREGROUND_START_DENIED -> stringResource(R.string.failed_foreground_denied)
        FailureReason.INTERNAL -> stringResource(R.string.failed_internal)
    }
}

@Composable
private fun NotificationsOffCard(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.notifications_off_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.notifications_off_body), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.action_open_notification_settings)) }
        }
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Extremely rare; nothing sensible to fall back to.
    }
}

/** @return false if the device has no handler for the system cast settings. */
private fun openCastSettings(context: Context): Boolean = try {
    context.startActivity(Intent(Settings.ACTION_CAST_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
