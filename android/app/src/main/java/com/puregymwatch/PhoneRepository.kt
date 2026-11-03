package com.puregymwatch

import android.content.Context
import android.net.Uri
import com.finnl.gymaccess.protocol.EntryCode
import com.finnl.gymaccess.protocol.SecureStore
import com.finnl.gymaccess.protocol.Wire
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationService
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AccessFailure(val status: String, override val message: String): Exception(message)

class PhoneRepository private constructor(private val context: Context) {
    private val storage = SecureStore(context, "gym-session-v1")
    private val cache = SecureStore(context, "gym-code-v1")
    private val pending = context.getSharedPreferences("gym-sync-v1", Context.MODE_PRIVATE)
    private val authService = AuthorizationService(context)
    private val mutex = Mutex()
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    @Volatile var lastError: String? = null
        private set
    private var lastAttempt = 0L
    private var rateLimitDelay = 60000L
    private var lastResult: JSONObject? = null
    fun sessionExpired() = pending.getBoolean("sessionExpired", false)
    fun revision() = pending.getLong("revision", 0)
    suspend fun syncNow() {
        syncPendingClear()
        if (signedIn()) refresh()
    }
    fun signedIn() = authState()?.isAuthorized == true
    private fun authState(): AuthState? = storage.read()?.let { runCatching { AuthState.jsonDeserialize(it) }.getOrNull() }
    fun cached(): EntryCode? = cache.read()?.let { runCatching { EntryCode.fromWire(JSONObject(it)) }.getOrNull() }?.takeIf { it.valid() }
    suspend fun completeLogin(state: AuthState) = mutex.withLock {
        storage.write(state.jsonSerializeString()); cache.clear(); lastResult = null; lastAttempt = 0
        lastError = null
        pending.edit().putBoolean("clearPending", false).putBoolean("sessionExpired", false).commit()
    }
    fun loginFailed() { lastError = "PureGym sign-in did not complete. Please try again." }
    private suspend fun token(): String = withContext(NonCancellable) {
        val state = authState() ?: throw AccessFailure("sign_in_required", "Sign in on your phone")
        suspendCancellableCoroutine { cont ->
            state.performActionWithFreshTokens(authService) { accessToken, _, exception ->
                // Called inside the repository mutex; no concurrent refresh or sign-out can overwrite this session.
                storage.write(state.jsonSerializeString())
                if (!cont.isActive) return@performActionWithFreshTokens
                if (exception != null || accessToken == null) {
                    if (exception?.error == "invalid_grant") {
                        cont.resumeWithException(AccessFailure("sign_in_required", "Your session expired. Sign in on your phone."))
                    } else cont.resumeWithException(AccessFailure("network_error", "Unable to refresh your session. Check your phone's internet."))
                } else cont.resume(accessToken)
            }
        }
    }
    suspend fun refresh(): JSONObject = mutex.withLock {
        if (!signedIn()) return@withLock publish(Wire.result("sign_in_required", message = "Sign in on your phone"))
        val now = System.currentTimeMillis()
        cached()?.takeIf { now < it.refreshAt }?.let { return@withLock publish(Wire.result("ready", it)) }
        if (now - lastAttempt < 5000) return@withLock lastResult?.let { JSONObject(it.toString()) } ?: Wire.result("service_error", message = "Please wait a moment and retry")
        lastAttempt = now
        try {
            var accessToken = token()
            var response = request(accessToken)
            if (response.first == 401) {
                authState()?.let { it.setNeedsTokenRefresh(true); storage.write(it.jsonSerializeString()) }
                accessToken = token()
                response = request(accessToken)
            }
            val code = com.finnl.gymaccess.protocol.QrResponse.parse(response.first, response.second, System.currentTimeMillis())
            cache.write(code.json().toString()); lastError = null
            publish(Wire.result("ready", code))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val status = when (e) { is AccessFailure -> e.status; is com.finnl.gymaccess.protocol.QrResponseFailure -> e.status; is IOException -> "network_error"; else -> "service_error" }
            val message = (e as? AccessFailure)?.message ?: (e as? com.finnl.gymaccess.protocol.QrResponseFailure)?.message ?: if (e is IOException) "Check your phone's internet and retry." else "Unable to load your entry code. Please retry."
            lastError = message
            if (status == "sign_in_required" || status == "access_denied") {
                cache.clear()
                if (status == "sign_in_required") { storage.clear(); pending.edit().putBoolean("sessionExpired", true).commit() }
            }
            val fallback = if (status == "network_error" || status == "service_error" || status == "rate_limited") cached() else null
            if (status == "rate_limited") lastAttempt = System.currentTimeMillis() + maxOf(0, rateLimitDelay - 5000)
            publish(Wire.result(if (fallback != null) "cached" else status, fallback, message))
        }
    }
    private suspend fun request(accessToken: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val operation = "mutation CreateQrAccessCode { createQrAccessCode { __typename qrCode refreshAt expiresAt refreshIn expiresIn errors { __typename ... on NotGrantedError { message code } ... on QrAccessCodeCreationFailedError { message code } } } }"
        val json = JSONObject().put("operationName", "CreateQrAccessCode").put("query", operation)
        val request = Request.Builder().url("https://wundergraph.picogym.world/api/graphql")
            .header("Authorization", "Bearer $accessToken").header("X-PureBrand", "uk")
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 429) rateLimitDelay = com.finnl.gymaccess.protocol.RetryWindow.milliseconds(response.header("Retry-After"), System.currentTimeMillis())
            response.code to (response.body?.string() ?: "")
        }
    }
    private suspend fun publish(value: JSONObject): JSONObject {
        val revision = maxOf(System.currentTimeMillis(), pending.getLong("revision", 0) + 1)
        pending.edit().putLong("revision", revision).commit()
        value.put("revision", revision)
        lastResult = value
        val item = PutDataMapRequest.create(Wire.STATE).apply {
            dataMap.putString("json", value.toString()); dataMap.putLong("updatedAt", revision)
        }.asPutDataRequest().setUrgent()
        try {
            Wearable.getDataClient(context).putDataItem(item).await()
            pending.edit().putBoolean("clearPending", false).commit()
        } catch (_: Exception) { /* A local QR remains usable; sync will retry on reconnection. */ }
        return JSONObject(value.toString())
    }
    suspend fun signOut() = mutex.withLock {
        storage.clear(); cache.clear(); lastError = null; lastResult = null; lastAttempt = 0
        pending.edit().putBoolean("clearPending", true).putBoolean("sessionExpired", false).commit()
        publish(Wire.result("signed_out", message = "Sign in on your phone"))
        Unit
    }
    suspend fun syncPendingClear() { if (pending.getBoolean("clearPending", false)) signOut() }
    companion object {
        val authority: Uri = Uri.parse("https://auth.puregym.com")
        // This is the registered callback recovered from the official client. State + PKCE apply to our own login only.
        val redirect: Uri = Uri.parse("puregymoidc:/authenticated")
        @Volatile private var instance: PhoneRepository? = null
        fun get(context: Context): PhoneRepository = instance ?: synchronized(this) {
            instance ?: PhoneRepository(context.applicationContext).also { instance = it }
        }
    }
}
