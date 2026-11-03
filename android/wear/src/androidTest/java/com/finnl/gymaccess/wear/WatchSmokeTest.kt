package com.finnl.gymaccess.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.finnl.gymaccess.protocol.*
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.graphics.Bitmap

@RunWith(AndroidJUnit4::class)
class WatchSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Test fun phoneSettingsPreserveQrPixelsWithStatusAndWithoutSizeControls() {
        val settings = DisplaySettings.get(context)
        var revision = System.currentTimeMillis()
        settings.accept(WatchSettings().json().toString(), revision++)
        val repo = WatchRepository.get(context)
        val now = System.currentTimeMillis()
        val code = EntryCode("synthetic-entry-" + "aB012_-".repeat(8), now + 60000, now + 120000)
        repo.accept(Wire.result("ready", code).put("revision", now))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun screenshot(): Bitmap {
                lateinit var bitmap: Bitmap
                val latch = java.util.concurrent.CountDownLatch(1)
                var result = -1
                scenario.onActivity { activity ->
                    bitmap = Bitmap.createBitmap(activity.window.decorView.width, activity.window.decorView.height, Bitmap.Config.ARGB_8888)
                    android.view.PixelCopy.request(activity.window, bitmap, { value -> result = value; latch.countDown() }, android.os.Handler(android.os.Looper.getMainLooper()))
                }
                assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(android.view.PixelCopy.SUCCESS, result)
                return bitmap
            }
            fun verify(number: Int) {
                var matched = false
                val deadline = System.currentTimeMillis() + 4000
                while (!matched && System.currentTimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    val bitmap = screenshot()
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val sizes = QrRenderer.sizes(code.payload, bitmap.width, bitmap.height)
                    val expected = QrRenderer.matrix(code.payload, sizes[sizes.size - number])
                    val left = (bitmap.width - expected.width) / 2
                    val top = (bitmap.height - expected.height) / 2
                    // QR pixels remain exact. Only the status band above the QR may contain text.
                    matched = pixels.indices.all { i ->
                        val x = i % bitmap.width - left; val y = i / bitmap.width - top
                        val statusBand = i / bitmap.width < top && i / bitmap.width >= top - (18 * context.resources.displayMetrics.density).toInt() - 2
                        val black = x in 0 until expected.width && y in 0 until expected.height && expected[x, y]
                        statusBand || (pixels[i] and 0xffffff) == if (black) 0 else 0xffffff
                    }
                    if (matched) {
                        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))))
                        assertEquals(code.payload, decoded.text)
                        File(context.getExternalFilesDir(null), "watch-settings-size-$number.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
                assertTrue("Size $number preserves exact QR pixels and keeps status outside the QR", matched)
            }
            verify(3)
            // Status transitions must not change the QR image or its scanning quiet zone.
            for ((message, loading) in listOf("Refreshing..." to true, "Saved code" to false, "Scan to enter" to false)) {
                repo.state.value = repo.state.value.copy(message = message, loading = loading)
                verify(3)
                val bitmap = screenshot()
                val top = (bitmap.height - QrRenderer.sizes(code.payload, bitmap.width, bitmap.height).let { it[it.size - 3] }) / 2
                val bandHeight = (18 * context.resources.displayMetrics.density).toInt()
                val pixels = IntArray(bitmap.width * bandHeight)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, top - bandHeight, bitmap.width, bandHeight)
                assertTrue("Status renders above the QR: $message", pixels.any { (it and 0xffffff) != 0xffffff })

            }
            for (number in listOf(2, 1)) {
                settings.accept(WatchSettings(number, 30).json().toString(), revision++)
                verify(number)
            }
            scenario.onActivity { activity ->
                val view = activity.window.decorView
                val now = android.os.SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(now, now, action, view.width / 2f, view.height / 2f, 0)
                    view.dispatchTouchEvent(event); event.recycle()
                }
                val properties = android.view.MotionEvent.PointerProperties().apply { id = 0 }
                val coordinates = android.view.MotionEvent.PointerCoords().apply { setAxisValue(android.view.MotionEvent.AXIS_SCROLL, 1f) }
                val rotary = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_SCROLL, 1, arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_ROTARY_ENCODER, 0)
                view.dispatchGenericMotionEvent(rotary); rotary.recycle()
                assertTrue(activity.window.attributes.screenBrightness > 0.9f)
            }
            verify(1)
            scenario.recreate(); verify(1)
            settings.accept(WatchSettings(2, 120).json().toString(), revision - 2)
            assertEquals(1, settings.state.value.size) // An older sync cannot overwrite the selection.
            settings.accept("{\"size\":4,\"closeSeconds\":0}", revision++)
            assertEquals(1, settings.state.value.size)
        }
    }
    @Test fun closesAfterConfiguredFifteenSeconds() = verifyClose(15)
    @Test fun closesAfterDefaultThirtySeconds() = verifyClose(30)
    private fun verifyClose(seconds: Int) {
        DisplaySettings.get(context).accept(WatchSettings(3, seconds).json().toString(), System.currentTimeMillis())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val start = android.os.SystemClock.elapsedRealtime()
            android.os.SystemClock.sleep((seconds - 2) * 1000L)
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
            while (scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED && android.os.SystemClock.elapsedRealtime() - start < (seconds + 4) * 1000L) android.os.SystemClock.sleep(100)
            assertEquals("Watch activity closes at its configured deadline", androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
        }
    }
    @Test fun signOutClearsCodeAndRejectsLateReplies() {
        val repo = WatchRepository.get(context)
        val now = System.currentTimeMillis()
        repo.accept(Wire.result("ready", EntryCode("TEST", now + 10000, now + 20000)).put("revision", now))
        assertNotNull(repo.state.value.code)
        repo.accept(Wire.result("signed_out").put("revision", now + 1))
        assertNull(repo.state.value.code)
        repo.accept(Wire.result("ready", EntryCode("STALE", now + 10000, now + 20000)).put("revision", now))
        assertNull(repo.state.value.code)
        assertTrue(repo.state.value.signedOut)
    }
    @Test fun encryptedCacheDoesNotContainPlainPayloadAndSignOutErasesIt() {
        val store = SecureStore(context, "instrumentation-only-cache")
        try {
            val payload = "PRIVATE-TEST-PAYLOAD-ONLY"
            store.write(payload)
            assertEquals(payload, store.read())
            val raw = context.getSharedPreferences("instrumentation-only-cache", 0).getString("value", "")!!
            assertFalse(raw.contains(payload))
            store.clear(); assertNull(store.read())
        } finally { store.clear() }
    }
}
