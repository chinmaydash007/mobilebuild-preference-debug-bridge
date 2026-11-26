package io.github.chinmaydash007.mobilebuild.preferences.sample

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val preferences = getSharedPreferences(PLAIN_STORE, Context.MODE_PRIVATE)
        if (!preferences.contains("sample.boolean")) {
            preferences.edit()
                .putBoolean("sample.boolean", true)
                .putInt("sample.count", 7)
                .putLong("sample.long", 9_007_199_254_740_991L)
                .putFloat("sample.ratio", 1.5f)
                .putString("sample.message", "Hello from the sample app")
                .putStringSet("sample.tags", setOf("debug", "mobile-build"))
                .commit()
        }

        val discoveredPreferences = getSharedPreferences(DISCOVERED_STORE, Context.MODE_PRIVATE)
        if (!discoveredPreferences.contains("discovered.enabled")) {
            discoveredPreferences.edit()
                .putBoolean("discovered.enabled", true)
                .putString("discovered.source", "plain preference discovery")
                .commit()
        }

        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply {
                text = "Preference Debug Bridge"
                textSize = 24f
            })
            addView(TextView(context).apply {
                text = "Open Mobile Build → Tools → Shared Preferences Inspector and select this debug app."
                textSize = 16f
                setPadding(0, (16 * density).toInt(), 0, 0)
            })
        }
        setContentView(
            layout,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    companion object {
        const val PLAIN_STORE = "sample_plain"
        const val DISCOVERED_STORE = "sample_discovered"
    }
}
