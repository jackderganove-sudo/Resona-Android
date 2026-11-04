/*
 * Resona for Android – fork of ArchiveTune (2026) by Rukamori and contributors.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.resona

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import moe.rukamori.archivetune.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fresh install, no Google account: walks the whole onboarding with "skip"
 * at the login step, then opens the main tabs. Needs cleared app data
 * (`pm clear`, see .github/scripts/resona-onboarding-test.sh). Regression
 * test for the crash on the last onboarding page (v1.0.1–v1.0.7).
 */
@RunWith(AndroidJUnit4::class)
class OnboardingWithoutLoginTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext

    private fun find(id: Int, timeout: Long = TIMEOUT): UiObject2? {
        val text = context.getString(id)
        return device.wait(Until.findObject(By.text(text)), timeout)
    }

    private fun tap(id: Int) {
        val target = find(id)
        assertNotNull("\"${context.resources.getResourceEntryName(id)}\" not on screen", target)
        target!!.click()
        device.waitForIdle()
    }

    private fun assertAppAlive(step: String) {
        assertEquals("Resona left the foreground after: $step", context.packageName, device.currentPackageName)
        val activities = device.executeShellCommand("dumpsys activity activities")
        assertFalse("Crash screen after: $step", activities.lines().any { "ResumedActivity" in it && "DebugActivity" in it })
    }

    @Test
    fun onboardingWithoutLoginReachesTheApp() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch)

        tap(R.string.onboarding_get_started)
        tap(R.string.onboarding_continue)
        tap(R.string.onboarding_login_skip)
        assertAppAlive("skipping the login")
        // The last page: this is where it crashed.
        tap(R.string.onboarding_finish)
        assertAppAlive("finishing the onboarding")

        // An older build lands on the optional update screen first; it must offer "Later".
        find(R.string.later, 15_000)?.click()
        val home = find(R.string.home, 45_000)
        val visible = device.findObjects(By.textContains("")).mapNotNull { it.text }.take(20)
        assertNotNull("Home tab missing after the onboarding; on screen: $visible", home)
        for (tab in listOf(R.string.search, R.string.filter_library, R.string.home)) {
            tap(tab)
            device.waitForIdle()
            Thread.sleep(2_000)
            assertAppAlive("opening tab ${context.getString(tab)}")
        }
    }

    private companion object {
        const val TIMEOUT = 30_000L
    }
}
