package io.github.chinmaydash007.mobilebuild.preferences.consumer

import android.content.Context
import io.github.chinmaydash007.mobilebuild.preferences.PreferenceDebugProvider
import io.github.chinmaydash007.mobilebuild.preferences.PreferenceStoreRegistry

class FixturePreferenceDebugProvider : PreferenceDebugProvider() {
    override fun registerStores(registry: PreferenceStoreRegistry) {
        registry.register("fixture", "Fixture preferences") { context ->
            context.getSharedPreferences("fixture", Context.MODE_PRIVATE)
        }
        registry.discoverPlainPreferences()
    }
}
