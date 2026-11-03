package com.finnl.gymaccess.wear

import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlin.math.floor
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    private val repo by lazy { WatchRepository.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val openedAt = android.os.SystemClock.elapsedRealtime()
                var warned = false
                launch {
                    DisplaySettings.get(this@MainActivity).state.collectLatest { settings ->
                        val remaining = settings.remainingMillis(android.os.SystemClock.elapsedRealtime() - openedAt)
                        if (settings.closeWarning && !warned && remaining > 0) {
                            delay((remaining - 3000).coerceAtLeast(0))
                            warned = true
                            val vibrator = getSystemService(android.os.Vibrator::class.java)
                            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(120, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                        }
                        delay(settings.remainingMillis(android.os.SystemClock.elapsedRealtime() - openedAt))
                        restoreDisplay()
                        finish()
                    }
                }
                launch { DisplaySettings.get(this@MainActivity).load() }
                launch { WatchUpdater.get(this@MainActivity).load() }
                repo.load()
                repo.request()
                while (isActive) {
                    delay(1000)
                    repo.tick()
                    if (repo.shouldRefresh()) repo.request()
                }
            }
        }
        setContent {
            val state by repo.state.collectAsState()
            val watchUpdate by WatchUpdater.get(this).state.collectAsState()
            val scope = rememberCoroutineScope()
            val display by DisplaySettings.get(this).state.collectAsState()
            val code = state.code?.takeIf { it.valid() }
            MaterialTheme {
                BoxWithConstraints(
                    Modifier.fillMaxSize().background(if (code != null) Color.White else Color(0xFF111314)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (code != null) {
                        val sizes = remember(code.payload, constraints.maxWidth, constraints.maxHeight) {
                            runCatching { QrRenderer.sizes(code.payload, constraints.maxWidth, constraints.maxHeight) }
                                .getOrDefault(emptyList())
                        }
                        val selectedPixels = sizes.getOrNull(sizes.size - display.size) ?: 0
                        val image = remember(code.payload, selectedPixels) {
                            runCatching {
                                val matrix = QrRenderer.matrix(code.payload, selectedPixels)
                                val edge = matrix.width
                                Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888).also { bitmap ->
                                    val pixels = IntArray(edge * edge) { i ->
                                        if (matrix[i % edge, i / edge]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                                    }
                                    bitmap.setPixels(pixels, 0, edge, 0, 0, edge, edge)
                                }.asImageBitmap()
                            }.getOrNull()
                        }
                        if (image != null) {
                            Canvas(Modifier.fillMaxSize().clickable(enabled = watchUpdate.ready, onClickLabel = "Open watch update") {
                                startActivity(android.content.Intent(this@MainActivity, UpdateActivity::class.java))
                            }) {
                                // Both source and destination use the same physical pixel dimensions.
                                val origin = IntOffset(
                                    floor((size.width - image.width) / 2f).toInt(),
                                    floor((size.height - image.height) / 2f).toInt(),
                                )
                                val extent = IntSize(image.width, image.height)
                                drawImage(image, srcSize = extent, dstOffset = origin, dstSize = extent, filterQuality = FilterQuality.None)
                            }
                            // Status sits in the white margin above the QR; skipped when the QR leaves no room, so it never covers modules.
                            val labelHeightPx = with(LocalDensity.current) { 18.dp.roundToPx() }
                            val freeAbovePx = (constraints.maxHeight - image.height) / 2
                            if (freeAbovePx >= labelHeightPx) {
                                // Just above the QR, where the round screen is widest, so long messages aren't clipped by the bezel.
                                Text(
                                    if (state.loading) "Refreshing..." else state.message,
                                    color = Color.Black, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center,
                                    modifier = Modifier.align(Alignment.TopCenter).offset { IntOffset(0, freeAbovePx - labelHeightPx) },
                                )
                            }
                            if (watchUpdate.ready) TextButton(onClick = {
                                startActivity(android.content.Intent(this@MainActivity, UpdateActivity::class.java))
                            }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                                Text("Update ready", color = Color.Black, fontSize = 10.sp)
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Unable to render code", color = Color.Black, textAlign = TextAlign.Center)
                                Button(onClick = { scope.launch { repo.request() } }) { Text("Retry") }
                            }
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 22.dp)) {
                            Text("PG ACCESS", color = Color(0xFFD5FF45), fontSize = 12.sp)
                            Spacer(Modifier.height(10.dp))
                            Text(state.message, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(10.dp))
                            if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), color = Color(0xFFD5FF45))
                            else Button(onClick = { scope.launch { repo.request(state.signedOut) } }) {
                                Text(if (state.signedOut) "Open on phone" else "Retry", fontSize = 12.sp)
                            }
                        }
                    }
                if (watchUpdate.ready && code == null) {
                    Box(Modifier.fillMaxSize().padding(top = 16.dp), contentAlignment = Alignment.TopCenter) {
                        TextButton(onClick = { startActivity(android.content.Intent(this@MainActivity, UpdateActivity::class.java)) }) {
                            Text("Update ready", fontSize = 10.sp, color = Color(0xFF166534))
                        }
                    }
                }
                }
                DisposableEffect(code != null) {
                    if (code != null) enableScanningDisplay()
                    onDispose { restoreDisplay() }
                }
            }
        }
    }

    private fun enableScanningDisplay() {
        window.insetsController?.hide(WindowInsets.Type.systemBars())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 1f }
    }

    private fun restoreDisplay() {
        window.insetsController?.show(WindowInsets.Type.systemBars())
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }

    override fun onPause() { restoreDisplay(); super.onPause() }
    override fun onResume() {
        super.onResume()
        if (repo.state.value.code?.valid() == true) enableScanningDisplay()
    }
}
