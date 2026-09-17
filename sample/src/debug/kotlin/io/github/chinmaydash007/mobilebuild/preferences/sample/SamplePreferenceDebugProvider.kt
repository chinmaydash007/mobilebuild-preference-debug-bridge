package io.github.chinmaydash007.mobilebuild.preferences.sample

import android.content.Context
import io.github.chinmaydash007.mobilebuild.preferences.PreferenceDebugProvider
import io.github.chinmaydash007.mobilebuild.preferences.PreferenceStoreRegistry

class SamplePreferenceDebugProvider : PreferenceDebugProvider() {
    override fun registerStores(registry: PreferenceStoreRegistry) {
        registry.register("sample-plain", "Sample preferences") { context ->
            context.getSharedPreferences(MainActivity.PLAIN_STORE, Context.MODE_PRIVATE)
        }
        registry.register("sample-encrypted", "Sample encrypted preferences") { context ->
            SampleEncryptedPreferences.open(context)
        }
    }
}
