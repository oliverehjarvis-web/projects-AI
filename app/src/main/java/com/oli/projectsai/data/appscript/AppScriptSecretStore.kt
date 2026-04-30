package com.oli.projectsai.data.appscript

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppScriptSecretStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences? by lazy { openOrNull() }

    private fun openOrNull(): SharedPreferences? = runCatching {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse {
        runCatching { context.deleteSharedPreferences(FILE_NAME) }
        runCatching {
            val key = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrNull()
    }

    fun secretRefFor(toolId: Long): String = "tool_$toolId"

    fun putSecret(toolId: Long, secret: String): String {
        val ref = secretRefFor(toolId)
        prefs?.edit()?.putString(ref, secret)?.apply()
        return ref
    }

    fun getSecret(secretRef: String?): String? {
        if (secretRef.isNullOrBlank()) return null
        return prefs?.getString(secretRef, null)
    }

    fun deleteSecret(secretRef: String?) {
        if (secretRef.isNullOrBlank()) return
        prefs?.edit()?.remove(secretRef)?.apply()
    }

    fun putString(key: String, value: String?) {
        prefs?.edit()?.let { e -> if (value == null) e.remove(key) else e.putString(key, value) }?.apply()
    }

    fun getString(key: String): String? = prefs?.getString(key, null)

    fun putLong(key: String, value: Long) {
        prefs?.edit()?.putLong(key, value)?.apply()
    }

    fun getLong(key: String, default: Long = 0L): Long = prefs?.getLong(key, default) ?: default

    fun clearOAuth() {
        prefs?.edit()?.apply {
            remove(KEY_OAUTH_ACCESS)
            remove(KEY_OAUTH_REFRESH)
            remove(KEY_OAUTH_EXPIRY)
            remove(KEY_OAUTH_ACCOUNT)
        }?.apply()
    }

    companion object {
        private const val FILE_NAME = "app_script_secrets"
        const val KEY_OAUTH_ACCESS = "oauth_access"
        const val KEY_OAUTH_REFRESH = "oauth_refresh"
        const val KEY_OAUTH_EXPIRY = "oauth_expiry_ms"
        const val KEY_OAUTH_ACCOUNT = "oauth_account_email"
    }
}
