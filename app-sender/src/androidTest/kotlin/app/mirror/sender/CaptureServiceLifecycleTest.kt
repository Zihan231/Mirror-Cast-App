package app.mirror.sender

import android.app.Activity
import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.mirror.sender.capture.CaptureRepository
import app.mirror.sender.capture.CaptureService
import app.mirror.sender.capture.CaptureState
import app.mirror.sender.capture.StopReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Service start/stop lifecycle smoke tests.
 *
 * The first two are hermetic: they need no user consent and must pass on every device. They prove
 * the service always reaches a terminal state and shuts itself down, i.e. that it neither crashes
 * (ForegroundServiceDidNotStartInTime) nor leaks a running service when Android refuses the
 * projection.
 *
 * [fullFlowWithSystemConsent] drives the real system dialog with UiAutomator. The dialog differs
 * between Android versions and OEMs, so it skips (assumption) instead of failing when the dialog
 * cannot be navigated.
 */
@RunWith(AndroidJUnit4::class)
class CaptureServiceLifecycleTest {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<MirrorApp>()
    private val repo: CaptureRepository get() = app.captureRepository
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    @Before
    fun setUp() {
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, "android.permission.POST_NOTIFICATIONS")
        instrumentation.runOnMainSync {
            repo.stop(StopReason.USER_REQUESTED)
            repo.resetToIdle()
        }
        awaitTrue("service from a previous test still running") { !isCaptureServiceRunning() }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { repo.stop(StopReason.USER_REQUESTED) }
        app.stopService(CaptureService.stopIntent(app))
    }

    @Test
    fun invalidConsent_endsInFailedState_andServiceStopsItself() {
        ActivityScenario.launch(MainActivity::class.java).use {
            // An empty Intent is not a valid MediaProjection grant. Depending on the Android version the
            // failure happens at startForeground() (14+: no grant) or at getMediaProjection().
            ContextCompat.startForegroundService(app, CaptureService.startIntent(app, Activity.RESULT_OK, Intent()))

            val terminal = awaitState("terminal state") { it is CaptureState.Failed || it is CaptureState.Stopped }
            assertTrue("expected Failed but was $terminal", terminal is CaptureState.Failed)
            awaitTrue("service did not stop itself") { !isCaptureServiceRunning() }
        }
    }

    @Test
    fun stopActionWhileIdle_isHarmless() {
        ActivityScenario.launch(MainActivity::class.java).use {
            app.startService(CaptureService.stopIntent(app))
            awaitTrue("service did not stop itself") { !isCaptureServiceRunning() }
            assertEquals(CaptureState.Idle, repo.state.value)
        }
    }

    @Test
    fun fullFlowWithSystemConsent() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val startLabel = app.getString(R.string.action_start)
            assumeTrue("Start button not found", device.wait(Until.hasObject(By.text(startLabel)), 5_000))
            device.findObject(By.text(startLabel)).click()

            assumeTrue("system consent dialog did not appear", device.wait(Until.hasObject(By.pkg("com.android.systemui")), 5_000))
            navigateConsentDialog()

            val capturing = pollState(20_000) { it is CaptureState.Capturing }
            assumeTrue("consent dialog could not be completed on this device (state=${repo.state.value})", capturing)
            assertTrue("service should be running while capturing", isCaptureServiceRunning())

            // Stop through the real notification action path.
            app.startService(CaptureService.stopIntent(app))
            awaitState("stopped") { it is CaptureState.Stopped }
            assertEquals(CaptureState.Stopped(StopReason.USER_REQUESTED), repo.state.value)
            awaitTrue("service did not stop") { !isCaptureServiceRunning() }
        }
    }

    // --- helpers -----------------------------------------------------------------------------

    /** Best effort across Android 10-16 consent UIs. Unknown layouts simply leave the dialog open. */
    private fun navigateConsentDialog() {
        // Android 14+: spinner with "Share one app" / "Share entire screen".
        device.findObject(By.res("com.android.systemui", "screen_share_mode_spinner"))?.let { spinner ->
            spinner.click()
            device.wait(Until.findObject(By.textContains("entire screen")), 2_000)?.click()
        }
        // Positive button: "Start now" (<=13), "Share screen"/"Start" (14+), "Next" on some builds.
        repeat(2) {
            val button = device.wait(Until.findObject(By.res("android", "button1")), 2_000) ?: return
            button.click()
            SystemClock.sleep(500)
        }
    }

    private fun isCaptureServiceRunning(): Boolean {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION") // still returns this app's own services, which is all we need
        return am.getRunningServices(Int.MAX_VALUE).any { it.service.className == CaptureService::class.java.name }
    }

    private fun pollState(timeoutMs: Long, predicate: (CaptureState) -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate(repo.state.value)) return true
            SystemClock.sleep(100)
        }
        return false
    }

    private fun awaitState(what: String, timeoutMs: Long = 10_000, predicate: (CaptureState) -> Boolean): CaptureState {
        assertTrue("timed out waiting for $what (state=${repo.state.value})", pollState(timeoutMs, predicate))
        return repo.state.value
    }

    private fun awaitTrue(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError(message)
    }
}
