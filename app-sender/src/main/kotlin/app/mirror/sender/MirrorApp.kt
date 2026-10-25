package app.mirror.sender

import android.app.Application
import app.mirror.sender.capture.CaptureRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MirrorApp : Application() {
    /** Also a seam for instrumented tests, which observe the lifecycle through it. */
    @Inject
    lateinit var captureRepository: CaptureRepository
}
