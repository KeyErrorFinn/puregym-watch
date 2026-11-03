package com.puregymwatch

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.finnl.gymaccess.protocol.EntryCode
import com.finnl.gymaccess.protocol.SecureStore
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import net.openid.appauth.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhonePreviewTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val payload = "synthetic-entry-" + "aB012_-".repeat(8)

    private fun seed(code: EntryCode) {
        // Instrumentation runs only in an isolated emulator, never against a real member session.
        val config = AuthorizationServiceConfiguration(Uri.parse("https://example.invalid/authorize"), Uri.parse("https://example.invalid/token"))
        val request = AuthorizationRequest.Builder(config, "synthetic-client", ResponseTypeValues.CODE, Uri.parse("gymtest:/callback")).build()
        val response = AuthorizationResponse.Builder(request).setAccessToken("SYNTHETIC-INSTRUMENTATION-TOKEN").setTokenType("Bearer").build()
        val state = AuthState(response, null)
        SecureStore(context, "gym-session-v1").write(state.jsonSerializeString())
        SecureStore(context, "gym-code-v1").write(code.json().toString())
        CodePreview.clear()
    }

    private fun clear() {
        SecureStore(context, "gym-session-v1").clear()
        SecureStore(context, "gym-code-v1").clear()
        CodePreview.clear()
    }

    @Test fun phoneScreenDisplaysExactlyTheCachedWatchCredential() {
        val now = System.currentTimeMillis()
        val code = EntryCode(payload, now + 60000, now + 584690000)
        seed(code)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                var decoded: Result? = null
                val deadline = System.currentTimeMillis() + 15000
                while (decoded == null && System.currentTimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    val bitmap = instrumentation.uiAutomation.takeScreenshot()
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    decoded = runCatching { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))) }.getOrNull()
                    if (decoded != null) File(context.getExternalFilesDir(null), "phone-preview.png").outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
                }
                assertNotNull("Phone QR must be visible and decodable", decoded)
                assertEquals(payload, decoded!!.text)
                assertEquals(4, (decoded!!.rawBytes[0].toInt() and 255) ushr 4)
                assertEquals("Viewing the phone must not replace the cached code", code, PhoneRepository.get(context).cached())
            }
        } finally { clear() }
    }

    @Test fun scanBrightnessRestoresOnFocusLossAndPause() {
        val now = System.currentTimeMillis()
        seed(EntryCode(payload, now + 60000, now + 120000))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setScanBrightness(true)
                    activity.onWindowFocusChanged(true)
                    assertEquals(1f, activity.window.attributes.screenBrightness)
                    activity.onWindowFocusChanged(false)
                    assertEquals(-1f, activity.window.attributes.screenBrightness)
                    activity.onWindowFocusChanged(true)
                    assertEquals(1f, activity.window.attributes.screenBrightness)
                    activity.setScanBrightness(false)
                    assertEquals(-1f, activity.window.attributes.screenBrightness)
                }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                scenario.onActivity { activity -> assertEquals(-1f, activity.window.attributes.screenBrightness) }
            }
        } finally { clear() }
    }

    @Test fun expiredCacheIsUnavailableToPreview() {
        val now = System.currentTimeMillis()
        seed(EntryCode(payload, now - 2000, now - 1000))
        try { assertNull(PhoneRepository.get(context).cached()) } finally { clear() }
    }
}
