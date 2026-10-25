package app.mirror.receiver.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ReceiverTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF7FD6CB),
            onPrimary = Color(0xFF00201C),
            surface = Color(0xFF0B0F14),
            onSurface = Color(0xFFE6EDF3),
            background = Color(0xFF0B0F14),
            onBackground = Color(0xFFE6EDF3),
            error = Color(0xFFFFB4AB),
        ),
        content = content,
    )
}
