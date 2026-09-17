package io.github.chinmaydash007.mobilebuild.preferences.sample

import android.app.Application

class DebugSampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val preferences = SampleEncryptedPreferences.open(this)
        if (!preferences.contains("secure.enabled")) {
            preferences.edit()
                .putBoolean("secure.enabled", false)
                .putString("secure.environment", "sample")
                .commit()
        }
    }
}
