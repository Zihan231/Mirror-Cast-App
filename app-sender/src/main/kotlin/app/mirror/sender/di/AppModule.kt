package app.mirror.sender.di

import android.content.Context
import android.os.Build
import app.mirror.core.webrtc.SenderLinkFactory
import app.mirror.core.webrtc.WebRtcRuntime
import app.mirror.core.webrtc.WebRtcSenderLinkFactory
import app.mirror.sender.capture.CaptureSessionFactory
import app.mirror.sender.capture.WebRtcCaptureSessionFactory
import app.mirror.sender.stream.DeviceInfo
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    abstract fun bindSessionFactory(impl: WebRtcCaptureSessionFactory): CaptureSessionFactory

    companion object {
        @Provides
        @Singleton
        fun provideWebRtcRuntime(@ApplicationContext context: Context): WebRtcRuntime = WebRtcRuntime(context)

        @Provides
        @Singleton
        fun provideSenderLinkFactory(runtime: WebRtcRuntime): SenderLinkFactory = WebRtcSenderLinkFactory(runtime)

        @Provides
        fun provideDeviceInfo(): DeviceInfo = DeviceInfo((Build.MODEL ?: "").trim().take(64).ifEmpty { "Android device" })
    }
}
