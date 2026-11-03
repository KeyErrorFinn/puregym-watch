package com.puregymwatch

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finnl.gymaccess.protocol.WatchUpdate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhoneUpdaterTest {
    @Test fun bundledWatchMatchesTheCompanionAndPhoneApkIsRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "test-watch-bundle.apk")
        try {
            context.assets.open("watch-update.apk").use { input -> file.outputStream().use { input.copyTo(it) } }
            val info = WatchUpdate.inspect(context, file, newer = false)
            assertTrue(info.version > 0)
            assertTrue(info.name.isNotBlank())
            val failure = runCatching { WatchUpdate.inspect(context, File(context.applicationInfo.sourceDir), newer = false) }.exceptionOrNull()
            assertNotNull("The phone APK must never be accepted as a watch update", failure)
        } finally { file.delete() }
    }
}
