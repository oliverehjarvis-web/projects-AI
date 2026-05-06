package com.oli.projectsai.features.privacy.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.pinStoreDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "privacy_pin"
)

/**
 * PBKDF2-hashed PIN persisted in DataStore. Gates access to secret projects.
 * Intentionally name-obfuscated ("privacy_pin") and stored as opaque hex to avoid drawing attention.
 */
@Singleton
class PinStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val store = context.pinStoreDataStore

    val isSet: Flow<Boolean> = store.data.map { it[KEY_HASH] != null && it[KEY_SALT] != null }

    suspend fun set(pin: String) {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin, salt)
        store.edit {
            it[KEY_SALT] = salt.toHex()
            it[KEY_HASH] = hash.toHex()
        }
    }

    suspend fun verify(pin: String): Boolean {
        val prefs = store.data.first()
        val saltHex = prefs[KEY_SALT] ?: return false
        val hashHex = prefs[KEY_HASH] ?: return false
        val salt = saltHex.fromHex()
        val expected = hashHex.fromHex()
        val actual = pbkdf2(pin, salt)
        return expected.constantTimeEquals(actual)
    }

    suspend fun clear() {
        store.edit {
            it.remove(KEY_SALT)
            it.remove(KEY_HASH)
        }
    }

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private companion object {
        val KEY_SALT = stringPreferencesKey("salt")
        val KEY_HASH = stringPreferencesKey("hash")
        const val SALT_BYTES = 16
        const val ITERATIONS = 120_000
        const val KEY_BITS = 256
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.fromHex(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

private fun ByteArray.constantTimeEquals(other: ByteArray): Boolean {
    if (size != other.size) return false
    var diff = 0
    for (i in indices) diff = diff or (this[i].toInt() xor other[i].toInt())
    return diff == 0
}
