package io.github.chinmaydash007.mobilebuild.preferences

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceTransactionTest {
    @Test
    fun malformedPreferenceValuesStayVisibleButReadOnly() {
        assertEquals("unsupported", PreferenceTransaction.typed(Float.NaN).getString("type"))
        assertEquals("unsupported", PreferenceTransaction.typed(setOf("item", 1)).getString("type"))
        assertEquals("unsupported", PreferenceTransaction.typed(2.0).getString("type"))
    }

    @Test
    fun savesEverySupportedTypeWithOneEditor() {
        val preferences = MemoryPreferences(
            mutableMapOf(
                "boolean" to false,
                "int" to 2,
                "long" to 3L,
                "float" to 1.5f,
                "string" to "before",
                "set" to setOf("a"),
            ),
        )
        val changes = JSONArray()
            .put(change("boolean", false, true))
            .put(change("int", 2, Int.MAX_VALUE))
            .put(change("long", 3L, Long.MAX_VALUE))
            .put(change("float", 1.5f, 2.5f))
            .put(change("string", "before", "after"))
            .put(change("set", setOf("a"), setOf("b", "a")))

        assertEquals("ok", PreferenceTransaction.save(preferences, request(changes)).getString("status"))
        assertEquals(1, preferences.commitCount)
        assertEquals(true, preferences.all["boolean"])
        assertEquals(Int.MAX_VALUE, preferences.all["int"])
        assertEquals(Long.MAX_VALUE, preferences.all["long"])
        assertEquals(2.5f, preferences.all["float"])
        assertEquals("after", preferences.all["string"])
        assertEquals(setOf("a", "b"), preferences.all["set"])
    }

    @Test
    fun changedOrMissingKeyBlocksTheEntireCommit() {
        val preferences = MemoryPreferences(mutableMapOf("flag" to false, "count" to 3))
        val changes = JSONArray()
            .put(change("flag", false, true))
            .put(change("count", 2, 4))
        val result = PreferenceTransaction.save(preferences, request(changes))

        assertEquals("conflict", result.getString("status"))
        assertEquals("count", result.getJSONArray("conflicts").getJSONObject(0).getString("key"))
        assertEquals(0, preferences.commitCount)
        assertEquals(false, preferences.all["flag"])

        val missing = PreferenceTransaction.save(
            preferences,
            request(JSONArray().put(change("missing", false, true))),
        )
        assertEquals("conflict", missing.getString("status"))
        assertFalse(preferences.contains("missing"))
    }

    @Test
    fun rejectsOverflowTypeChangesDuplicateKeysAndUnsupportedWrites() {
        val preferences = MemoryPreferences(mutableMapOf("count" to 1, "unsupported" to 2.0))

        val overflow = change("count", 1, 2)
        overflow.getJSONObject("after").put("value", "2147483648")
        assertEquals(
            "failed",
            PreferenceTransaction.save(preferences, request(JSONArray().put(overflow))).getString("status"),
        )

        val wrongType = change("count", 1, 2).put("after", PreferenceTransaction.typed(true))
        assertEquals(
            "failed",
            PreferenceTransaction.save(preferences, request(JSONArray().put(wrongType))).getString("status"),
        )

        assertEquals(
            "failed",
            PreferenceTransaction.save(
                preferences,
                request(JSONArray().put(change("count", 1, 2)).put(change("count", 1, 3))),
            ).getString("status"),
        )

        val unsupported = JSONObject()
            .put("key", "unsupported")
            .put("before", PreferenceTransaction.typed(2.0))
            .put("after", PreferenceTransaction.typed(3.0))
        assertEquals(
            "failed",
            PreferenceTransaction.save(preferences, request(JSONArray().put(unsupported))).getString("status"),
        )
    }

    @Test
    fun reportsCommitAndReadFailuresWithoutChangingValues() {
        val preferences = MemoryPreferences(mutableMapOf("count" to 1)).apply {
            commitSucceeds = false
        }
        assertEquals(
            "failed",
            PreferenceTransaction.save(
                preferences,
                request(JSONArray().put(change("count", 1, 2))),
            ).getString("status"),
        )
        assertEquals(1, preferences.all["count"])
        assertTrue(preferences.contains("count"))

        preferences.readThrows = true
        assertEquals(
            "failed",
            PreferenceTransaction.save(
                preferences,
                request(JSONArray().put(change("count", 1, 2))),
            ).getString("status"),
        )
    }

    private fun change(key: String, before: Any, after: Any): JSONObject = JSONObject()
        .put("key", key)
        .put("before", PreferenceTransaction.typed(before))
        .put("after", PreferenceTransaction.typed(after))

    private fun request(changes: JSONArray): JSONObject = JSONObject()
        .put("version", BridgeProtocol.VERSION)
        .put("storeID", "fixture")
        .put("changes", changes)
}

internal class MemoryPreferences(
    private val contents: MutableMap<String, Any>,
) : SharedPreferences {
    var commitCount = 0
    var commitSucceeds = true
    var readThrows = false

    override fun getAll(): MutableMap<String, *> {
        if (readThrows) throw IllegalStateException("read failed")
        return contents.toMutableMap()
    }

    override fun getString(key: String?, defValue: String?): String? = contents[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (contents[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = contents[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = contents[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = contents[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = contents[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = contents.containsKey(key)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun edit(): SharedPreferences.Editor = Editor()

    private inner class Editor : SharedPreferences.Editor {
        private val staged = mutableMapOf<String, Any>()

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            if (key != null && value != null) staged[key] = value
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
            if (key != null && values != null) staged[key] = values.toSet()
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            if (key != null) staged[key] = value
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            if (key != null) staged[key] = value
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            if (key != null) staged[key] = value
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            if (key != null) staged[key] = value
        }

        override fun remove(key: String?): SharedPreferences.Editor = this
        override fun clear(): SharedPreferences.Editor = this

        override fun commit(): Boolean {
            commitCount++
            if (commitSucceeds) contents.putAll(staged)
            return commitSucceeds
        }

        override fun apply() {
            commit()
        }
    }
}
