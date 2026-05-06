package com.oli.projectsai.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Base for typed DataStore wrappers. Subclasses declare their `Preferences.Key`s and expose
 * named `Flow<T>` getters + suspend setters built on top of these helpers, removing the
 * read/edit boilerplate that's otherwise copy-pasted per integration.
 *
 * Each subclass still owns its own top-level `preferencesDataStore(name = "...")` extension
 * — DataStore requires that delegate at file scope so it can enforce one instance per file name.
 */
abstract class SettingsStore(private val store: DataStore<Preferences>) {

    protected fun stringFlow(key: Preferences.Key<String>, default: String = ""): Flow<String> =
        store.data.map { it[key] ?: default }

    protected fun longFlow(key: Preferences.Key<Long>, default: Long = 0L): Flow<Long> =
        store.data.map { it[key] ?: default }

    protected fun intFlow(key: Preferences.Key<Int>, default: Int = 0): Flow<Int> =
        store.data.map { it[key] ?: default }

    protected fun boolFlow(key: Preferences.Key<Boolean>, default: Boolean = false): Flow<Boolean> =
        store.data.map { it[key] ?: default }

    protected inline fun <reified T : Enum<T>> enumFlow(
        key: Preferences.Key<String>,
        default: T
    ): Flow<T> = data.map { prefs ->
        val raw = prefs[key] ?: return@map default
        runCatching { enumValueOf<T>(raw) }.getOrDefault(default)
    }

    protected suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }

    protected suspend fun <T : Enum<T>> setEnum(key: Preferences.Key<String>, value: T) {
        store.edit { it[key] = value.name }
    }

    @PublishedApi
    internal val data: Flow<Preferences> get() = store.data
}
