package com.finnl.gymaccess.wear

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finnl.gymaccess.protocol.WatchUpdate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpdaterTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val installedVersion get() = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    private val fixture get() = File(context.getExternalFilesDir(null), "test-newer.apk")
    private fun rejects(expected: String, action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        assertNotNull("Expected update rejection: $expected", failure)
        assertTrue("Expected $expected, got ${failure?.message}", failure?.message?.contains(expected) == true)
    }
    @Test fun acceptsOnlyMatchingNewerWatchPackageAndCompleteTransfer() {
        val info = WatchUpdate.validateTransfer(context, fixture, fixture.length(), WatchUpdate.sha256(fixture), installedVersion + 1)
        assertEquals(installedVersion + 1, info.version)
        rejects("incomplete") { WatchUpdate.validateTransfer(context, fixture, fixture.length() + 1, WatchUpdate.sha256(fixture), installedVersion + 1) }
        rejects("incomplete") { WatchUpdate.validateTransfer(context, fixture, fixture.length(), "0".repeat(64), installedVersion + 1) }
        rejects("version") { WatchUpdate.validateTransfer(context, fixture, fixture.length(), WatchUpdate.sha256(fixture), installedVersion + 2) }
        rejects("up to date") { WatchUpdate.inspect(context, File(context.applicationInfo.sourceDir)) }
        rejects("another app") { WatchUpdate.inspect(context, File(instrumentation.context.applicationInfo.sourceDir)) }
        rejects("signature") { WatchUpdate.inspect(context, File(context.getExternalFilesDir(null), "test-wrong-signer.apk")) }
    }
    @Test fun installationUsesReadablePrivateUriAndSystemConfirmation() {
        val baselineVersion = installedVersion
        val updater = WatchUpdater.get(context)
        fixture.copyTo(updater.apk, overwrite = true)
        updater.state.value = UpdateState(true, "Update ready", "test")
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", updater.apk)
            assertEquals("content", uri.scheme)
            assertEquals(fixture.length(), context.contentResolver.openFileDescriptor(uri, "r")!!.use { it.statSize })
            assertNotNull(context.packageManager.resolveActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")), 0))
            ActivityScenario.launch(UpdateActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { it.install() }
                var installer = false
                val deadline = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < deadline && !installer) {
                    val name = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() ?: ""
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    installer = (name.contains("packageinstaller") || name.contains("permissioncontroller")) &&
                        root?.findAccessibilityNodeInfosByText("Update")?.isNotEmpty() == true
                }
                assertTrue("System installation confirmation must open", installer)
                Thread.sleep(700) // Allow the system dialog frame to be presented before capturing it.
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                File(context.getExternalFilesDir(null), "update-system-prompt.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                // Do not approve installation from the test: preserve the installed baseline version.
                assertEquals(baselineVersion, context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode)
            }
        } finally { updater.apk.delete(); updater.state.value = UpdateState() }
    }
}
