package io.github.chinmaydash007.mobilebuild.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * Collects the preference stores that a debug build explicitly exposes to Mobile Build.
 *
 * Store factories are retained without being invoked. They are opened only when the provider
 * receives a request, after normal application startup has completed.
 */
class PreferenceStoreRegistry internal constructor() {
    private val registered = linkedMapOf<String, RegisteredPreferenceStore>()

    /**
     * Registers one editable preference store.
     *
     * The [id] is a stable protocol identifier and does not need to match an on-disk filename.
     * The [open] function must use the same configuration as the application, including any
     * encryption key alias and schemes. It may throw when the store is unavailable; Mobile Build
     * will then show the store as unavailable without exposing the exception.
     */
    fun register(
        id: String,
        label: String,
        open: (Context) -> SharedPreferences,
    ) {
        require(id.isNotBlank()) { "Preference store ID must not be blank" }
        require(label.isNotBlank()) { "Preference store label must not be blank" }
        require(id !in registered) { "Preference store ID is already registered: $id" }
        registered[id] = RegisteredPreferenceStore(id, label, open)
    }

    internal fun stores(): List<RegisteredPreferenceStore> = registered.values.toList()
}

internal data class RegisteredPreferenceStore(
    val id: String,
    val label: String,
    val open: (Context) -> SharedPreferences,
)
