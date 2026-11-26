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
    private val discoveryExclusions = linkedSetOf<String>()
    private var discoveryEnabled = false

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
        registered[id] = RegisteredPreferenceStore(
            id = id,
            label = label,
            kind = "registered",
            open = open,
        )
    }

    /**
     * Opts this provider into discovering ordinary preference XML files owned by the app.
     *
     * Discovery is disabled unless this method is called. [excludedFileNames] accepts preference
     * basenames with or without the `.xml` suffix. Repeated calls are safe and merge exclusions.
     * Explicit registrations always take priority over discovered files with the same ID.
     *
     * Encrypted preferences should still be explicitly registered with their real configuration
     * or excluded here. The bridge blocks known AndroidX encrypted files and encrypted-looking
     * filenames, but no generic detector can recognize every custom encryption format.
     */
    fun discoverPlainPreferences(
        excludedFileNames: Set<String> = emptySet(),
    ) {
        excludedFileNames.forEach { fileName ->
            discoveryExclusions += PlainPreferenceDiscovery.normalizedExclusions(fileName)
        }
        discoveryEnabled = true
    }

    internal fun stores(): List<RegisteredPreferenceStore> = registered.values.toList()

    internal fun stores(context: Context): List<RegisteredPreferenceStore> {
        val explicit = stores()
        if (!discoveryEnabled) return explicit
        val excluded = discoveryExclusions + registered.keys
        return explicit + PlainPreferenceDiscovery.stores(context, excluded)
    }
}

internal data class RegisteredPreferenceStore(
    val id: String,
    val label: String,
    val kind: String,
    val unavailableMessage: String? = null,
    val validate: (Map<String, *>) -> PreferenceStoreRejection? = { null },
    private val open: (Context) -> SharedPreferences,
) {
    fun resolve(context: Context): ResolvedPreferenceStore {
        if (unavailableMessage != null) {
            throw PreferenceStoreUnavailable(kind, unavailableMessage)
        }
        val preferences = open(context)
        val values = preferences.all
        validate(values)?.let { rejection ->
            throw PreferenceStoreUnavailable(rejection.kind, rejection.message)
        }
        return ResolvedPreferenceStore(preferences, values)
    }
}

internal data class ResolvedPreferenceStore(
    val preferences: SharedPreferences,
    val values: Map<String, *>,
)

internal data class PreferenceStoreRejection(
    val kind: String,
    val message: String,
)

internal class PreferenceStoreUnavailable(
    val exposedKind: String,
    val exposedMessage: String,
) : Exception()
