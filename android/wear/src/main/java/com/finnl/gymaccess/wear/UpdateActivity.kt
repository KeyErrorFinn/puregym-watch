package com.finnl.gymaccess.wear

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.finnl.gymaccess.protocol.WatchUpdate

class UpdateActivity : ComponentActivity() {
    private var message by mutableStateOf("")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val update by WatchUpdater.get(this).state.collectAsState()
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFD5FF45), onPrimary = Color(0xFF111314), background = Color(0xFF111314), surface = Color(0xFF202426))) {
                Surface(color = Color(0xFF111314), modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Watch update", color = Color.White, fontSize = 18.sp)
                        Text(if (message.isNotEmpty()) message else "Install PG Access ${update.name}. Your login and saved settings stay in place.", color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center)
                        Button(onClick = { install() }, enabled = update.ready) { Text("Install") }
                        TextButton(onClick = { finish() }) { Text("Later") }
                    }
                }
            }
        }
    }
    internal fun install() {
        try {
            val file = WatchUpdater.get(this).apk
            WatchUpdate.inspect(this, file)
            if (!packageManager.canRequestPackageInstalls()) {
                message = "Allow PG Access to install updates, then return here and tap Install again."
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            message = "Confirm installation in the watch's system prompt. If cancelled, tap Install to retry."
        } catch (_: ActivityNotFoundException) {
            message = "This watch firmware does not provide the required installation screen. Use manual installation for now."
        } catch (_: Exception) {
            message = "Unable to install this update. Send it again from your phone."
        }
    }
}
