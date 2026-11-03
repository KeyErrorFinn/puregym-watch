package com.puregymwatch

import android.content.Intent
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.*
import com.facebook.react.uimanager.ViewManager
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

class GymPackage: ReactPackage {
    override fun createNativeModules(context: ReactApplicationContext): List<NativeModule> = listOf(GymModule(context))
    override fun createViewManagers(context: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}
class GymModule(private val context: ReactApplicationContext): ReactContextBaseJavaModule(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updater = PhoneUpdater(context)
    private val repository = PhoneRepository.get(context)
    override fun getName() = "GymAccess"
    override fun getConstants(): Map<String, Any> = mapOf("appVersion" to BuildConfig.VERSION_NAME)
    @ReactMethod fun setScanBrightness(enabled: Boolean) {
        com.facebook.react.bridge.UiThreadUtil.runOnUiThread {
            (context.currentActivity as? MainActivity)?.setScanBrightness(enabled)
        }
    }
    @ReactMethod fun watchSettings(promise: Promise) { scope.launch {
        try {
            val settings = PhoneSettings.get(context)
            val value = settings.current()
            promise.resolve(Arguments.createMap().apply { putInt("size", value.size); putInt("closeSeconds", value.closeSeconds); putBoolean("closeWarning", value.closeWarning) })
            settings.sync()
        } catch (_: Exception) { promise.reject("SETTINGS_FAILED", "Unable to read watch settings.") }
    } }
    @ReactMethod fun saveWatchSettings(size: Int, closeSeconds: Int, closeWarning: Boolean, promise: Promise) { scope.launch {
        try { PhoneSettings.get(context).save(com.finnl.gymaccess.protocol.WatchSettings(size, closeSeconds, closeWarning)); promise.resolve(null) }
        catch (_: Exception) { promise.reject("SETTINGS_FAILED", "Unable to save watch settings.") }
    } }
    @ReactMethod fun status(promise: Promise) { scope.launch {
        try {
            repository.syncPendingClear()
            val connected = runCatching { Wearable.getCapabilityClient(context).getCapability("gym_access_watch_v1", com.google.android.gms.wearable.CapabilityClient.FILTER_REACHABLE).await().nodes.isNotEmpty() }.getOrDefault(false)
            val code = repository.cached()
            promise.resolve(Arguments.createMap().apply {
                putBoolean("signedIn", repository.signedIn()); putBoolean("watchConnected", connected)
                putBoolean("sessionExpired", repository.sessionExpired())
                val receipts = context.getSharedPreferences("watch-receipts", 0)
                putBoolean("codeSynced", repository.revision() > 0 && receipts.getLong("code", -1) == repository.revision())
                putBoolean("settingsSynced", receipts.getLong("settings", -1) == PhoneSettings.get(context).revision())
                putBoolean("codeReady", code != null); putString("error", repository.lastError)
            })
        } catch (_: Exception) { promise.reject("STATUS_FAILED", "Unable to read connection status.") }
    } }
    @ReactMethod fun syncNow(promise: Promise) { scope.launch {
        try { PhoneSettings.get(context).sync(); repository.syncNow(); promise.resolve(null) }
        catch (_: Exception) { promise.reject("SYNC_FAILED", "Unable to sync. Please retry.") }
    } }
    @ReactMethod fun preview(maximumPixels: Double, promise: Promise) { scope.launch {
        try {
            // Take one cached snapshot. Never call refresh() from a preview or polling action.
            val code = repository.cached()
            if (code == null || !repository.signedIn()) {
                CodePreview.clear()
                promise.resolve(null)
            } else {
                val (uri, edge) = CodePreview.image(code, maximumPixels.toInt())
                promise.resolve(Arguments.createMap().apply {
                    putString("uri", uri); putInt("pixels", edge)
                    putDouble("refreshAt", code.refreshAt.toDouble())
                    putDouble("expiresAt", code.expiresAt.toDouble())
                })
            }
        } catch (_: Exception) { promise.reject("PREVIEW_FAILED", "Unable to display your saved entry code.") }
    } }
    @ReactMethod fun signIn(promise: Promise) {
        try { context.startActivity(Intent(context, AuthActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); promise.resolve(null) }
        catch (_: Exception) { promise.reject("LOGIN_FAILED", "Unable to open PureGym sign-in.") }
    }
    @ReactMethod fun refresh(promise: Promise) { scope.launch {
        try {
            val value = repository.refresh()
            if (value.optString("status") !in listOf("ready", "cached")) promise.reject("QR_FAILED", value.optString("message", "Unable to load entry code."))
            else promise.resolve(null)
        } catch (_: Exception) { promise.reject("QR_FAILED", "Unable to load entry code.") }
    } }
    @ReactMethod fun signOut(promise: Promise) { scope.launch {
        try { repository.signOut(); CodePreview.clear(); promise.resolve(null) } catch (_: Exception) { promise.reject("SIGNOUT_FAILED", "Unable to clear the session.") }
    } }
    @ReactMethod fun watchUpdateStatus(promise: Promise) { scope.launch {
        try {
            val update = updater.status()
            promise.resolve(Arguments.createMap().apply {
                putString("version", update.first); putString("message", update.second)
                putString("installedVersion", updater.installedVersion())
            })
        } catch (_: Exception) { promise.resolve(null) }
    } }
    @ReactMethod fun updateWatch(promise: Promise) { scope.launch {
        try { updater.send(); promise.resolve(null) }
        catch (error: Exception) { promise.reject("UPDATE_FAILED", error.message ?: "Unable to send watch update.") }
    } }
    override fun invalidate() { scope.cancel(); super.invalidate() }
}
