package com.puregymwatch

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.*
import net.openid.appauth.*

class AuthActivity: Activity() {
    private lateinit var service: AuthorizationService
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        service = AuthorizationService(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(16, 23, 22))
            addView(ProgressBar(this@AuthActivity))
            addView(TextView(this@AuthActivity).apply {
                text = "Opening PureGym sign-in…"; setTextColor(Color.WHITE); textSize = 17f
                setPadding(24, 32, 24, 24)
            })
        })
        if (savedInstanceState != null) return
        AuthorizationServiceConfiguration.fetchFromIssuer(PhoneRepository.authority) { config, error ->
            if (config == null || error != null) { failed(); return@fetchFromIssuer }
            if (isFinishing || isDestroyed) return@fetchFromIssuer
            val request = try { AuthorizationRequest.Builder(config, "mobile.client", ResponseTypeValues.CODE, PhoneRepository.redirect)
                .setScopes("openid", "profile", "email", "offline_access", "pgcapi", "account")
                .setUiLocales("en-GB")
                .setAdditionalParameters(mapOf("brand" to "pguk"))
                .build() } catch (_: Exception) { failed(); return@fetchFromIssuer }
            runOnUiThread {
                try { startActivityForResult(service.getAuthorizationRequestIntent(request), 101) }
                catch (_: Exception) { failed() }
            }
        }
    }
    @Deprecated("Activity result bridge for AppAuth")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 101) return
        val response = data?.let { AuthorizationResponse.fromIntent(it) }
        val error = data?.let { AuthorizationException.fromIntent(it) }
        if (response == null || error != null) { failed(); return }
        val state = AuthState(response, error)
        service.performTokenRequest(response.createTokenExchangeRequest()) { token, exception ->
            if (token == null || exception != null) { failed(); return@performTokenRequest }
            state.update(token, exception)
            scope.launch {
                try {
                    val repo = PhoneRepository.get(this@AuthActivity)
                    repo.completeLogin(state)
                    withContext(Dispatchers.IO) { repo.refresh() }
                } catch (_: Exception) { PhoneRepository.get(this@AuthActivity).loginFailed() }
                finish()
            }
        }
    }
    private fun failed() { runOnUiThread { PhoneRepository.get(this).loginFailed(); finish() } }
    override fun onDestroy() { service.dispose(); scope.cancel(); super.onDestroy() }
}
