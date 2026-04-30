package com.oli.projectsai.data.appscript

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.oli.projectsai.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps Google Identity Services Authorization for the Apps Script Execution API.
 *
 * Token model: Identity Services manages caching internally; calling [authorizeSilent]
 * after the user has consented returns a fresh access token without UI. We cache the
 * token + expiry locally so that [accessToken] avoids the round-trip on every call.
 */
@Singleton
class GoogleOAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secretStore: AppScriptSecretStore
) {

    sealed class BeginResult {
        object Success : BeginResult()
        data class NeedsConsent(val pendingIntent: PendingIntent) : BeginResult()
        data class Error(val message: String) : BeginResult()
    }

    private val _signedIn = MutableStateFlow(secretStore.getString(AppScriptSecretStore.KEY_OAUTH_ACCOUNT) != null)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _accountEmail = MutableStateFlow(secretStore.getString(AppScriptSecretStore.KEY_OAUTH_ACCOUNT))
    val accountEmail: StateFlow<String?> = _accountEmail.asStateFlow()

    /** Web OAuth client ID — must match the GCP project that contains the user's Apps Script. */
    val webClientIdConfigured: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    /**
     * Kick off authorization. If the user has already granted consent for these scopes,
     * resolves to [BeginResult.Success] and the access token is cached. Otherwise returns
     * [BeginResult.NeedsConsent] with a PendingIntent the UI must launch.
     */
    suspend fun beginSignIn(): BeginResult = withContext(Dispatchers.IO) {
        if (!webClientIdConfigured) {
            return@withContext BeginResult.Error(
                "GOOGLE_WEB_CLIENT_ID not set in local.properties. " +
                    "Add the Web OAuth client ID from the Google Cloud project that owns your Apps Script."
            )
        }
        val request = buildAuthorizationRequest()
        val client = Identity.getAuthorizationClient(context)
        try {
            val result = awaitAuthorization(client, request)
            handleResult(result)
        } catch (t: Throwable) {
            BeginResult.Error(t.message ?: "Authorization failed")
        }
    }

    /** Call from the activity result after launching a NeedsConsent PendingIntent. */
    fun completeSignIn(data: Intent?): Result<Unit> = runCatching {
        val client = Identity.getAuthorizationClient(context)
        val result = client.getAuthorizationResultFromIntent(data)
        when (val r = handleResult(result)) {
            is BeginResult.Success -> Unit
            is BeginResult.Error -> error(r.message)
            is BeginResult.NeedsConsent -> error("Consent still required after activity result.")
        }
    }

    /**
     * Returns a usable access token, refreshing silently if expired. Returns null if the
     * user has not yet connected, in which case the UI must prompt sign-in. Network call
     * only when the cached token is within [REFRESH_SLACK_MS] of expiry.
     */
    suspend fun accessToken(): String? = withContext(Dispatchers.IO) {
        val cached = secretStore.getString(AppScriptSecretStore.KEY_OAUTH_ACCESS)
        val expiry = secretStore.getLong(AppScriptSecretStore.KEY_OAUTH_EXPIRY)
        if (cached != null && System.currentTimeMillis() < expiry - REFRESH_SLACK_MS) return@withContext cached
        if (!webClientIdConfigured) return@withContext null
        val request = buildAuthorizationRequest()
        val client = Identity.getAuthorizationClient(context)
        val result = runCatching { awaitAuthorization(client, request) }.getOrElse {
            return@withContext cached  // fall back; caller will see 401 and surface reconnect
        }
        if (result.hasResolution()) {
            // Consent expired or revoked. Clear so UI shows reconnect.
            secretStore.clearOAuth()
            _signedIn.value = false
            _accountEmail.value = null
            null
        } else {
            persist(result)
            result.accessToken
        }
    }

    fun signOut() {
        secretStore.clearOAuth()
        _signedIn.value = false
        _accountEmail.value = null
    }

    private fun handleResult(result: AuthorizationResult): BeginResult =
        if (result.hasResolution()) {
            BeginResult.NeedsConsent(result.pendingIntent!!)
        } else {
            persist(result)
            BeginResult.Success
        }

    private fun persist(result: AuthorizationResult) {
        val token = result.accessToken
        if (!token.isNullOrBlank()) {
            secretStore.putString(AppScriptSecretStore.KEY_OAUTH_ACCESS, token)
            // Identity Services tokens last 1 hour; bake in a slightly conservative expiry.
            secretStore.putLong(
                AppScriptSecretStore.KEY_OAUTH_EXPIRY,
                System.currentTimeMillis() + DEFAULT_TOKEN_LIFETIME_MS
            )
        }
        result.serverAuthCode?.let { secretStore.putString(AppScriptSecretStore.KEY_OAUTH_REFRESH, it) }
        // grantedScopes / accountName aren't directly on AuthorizationResult; the email is
        // captured via the GetGoogleIdOption flow on the consent screen. For now treat
        // "we have a token" as "signed in".
        if (!token.isNullOrBlank()) {
            secretStore.putString(AppScriptSecretStore.KEY_OAUTH_ACCOUNT, "(connected)")
            _signedIn.value = true
            _accountEmail.value = "(connected)"
        }
    }

    private fun buildAuthorizationRequest(): AuthorizationRequest {
        val scopes = REQUIRED_SCOPES.map { Scope(it) }
        return AuthorizationRequest.Builder()
            .setRequestedScopes(scopes)
            .requestOfflineAccess(BuildConfig.GOOGLE_WEB_CLIENT_ID, true)
            .build()
    }

    private suspend fun awaitAuthorization(
        client: com.google.android.gms.auth.api.identity.AuthorizationClient,
        request: AuthorizationRequest
    ): AuthorizationResult = suspendCancellableCoroutine { cont ->
        client.authorize(request)
            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    }

    companion object {
        /**
         * Scopes requested at sign-in. The user's Apps Script must operate within these
         * — if the script touches Gmail/Calendar/Drive in ways not covered here, the API
         * call will return an authorization error and the user must reconnect with the
         * extra scope. Add scopes here as needs grow.
         */
        val REQUIRED_SCOPES = listOf(
            "https://www.googleapis.com/auth/script.projects",
            "https://www.googleapis.com/auth/drive.readonly",
            "https://www.googleapis.com/auth/spreadsheets",
            "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/calendar.readonly"
        )
        private const val DEFAULT_TOKEN_LIFETIME_MS = 55L * 60L * 1000L
        private const val REFRESH_SLACK_MS = 60L * 1000L
    }
}
