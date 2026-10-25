package app.mirror.receiver.di

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import app.mirror.core.webrtc.ReceiverLinkFactory
import app.mirror.core.webrtc.WebRtcReceiverLinkFactory
import app.mirror.core.webrtc.WebRtcRuntime
import app.mirror.receiver.session.ReceiverDeviceInfo
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.webrtc.audio.JavaAudioDeviceModule

@Module
@InstallIn(SingletonComponent::class)
object ReceiverModule {

    @Provides
    @Singleton
    fun provideWebRtcRuntime(@ApplicationContext context: Context): WebRtcRuntime {
        // Received audio goes to the TV's media output. Nothing is ever recorded: the module only starts
        // capturing when a local audio track is sent, and this app never sends one (no RECORD_AUDIO).
        val audio = JavaAudioDeviceModule.builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setUseStereoOutput(true)
            .createAudioDeviceModule()
        return WebRtcRuntime(context, audio)
    }

    @Provides
    @Singleton
    fun provideReceiverLinkFactory(runtime: WebRtcRuntime): ReceiverLinkFactory = WebRtcReceiverLinkFactory(runtime)

    @Provides
    fun provideDeviceInfo(@ApplicationContext context: Context): ReceiverDeviceInfo {
        // The name the user gave the TV in system settings, if any; shown on the sender.
        val friendly = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
        val name = (friendly?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "").trim().take(64)
        return ReceiverDeviceInfo(name.ifEmpty { "TV" })
    }
}
