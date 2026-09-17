package io.github.chinmaydash007.mobilebuild.preferences

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Performs one type-preserving, conflict-aware SharedPreferences transaction. */
internal object PreferenceTransaction {
    fun typed(value: Any?): JSONObject = when (value) {
        is Boolean -> JSONObject().put("type", "boolean").put("value", value)
        is Int -> JSONObject().put("type", "int").put("value", value)
        is Long -> JSONObject().put("type", "long").put("value", value.toString())
        is Float -> if (value.isFinite()) {
            JSONObject().put("type", "float").put("value", value.toString())
        } else {
            unsupported(value)
        }
        is String -> JSONObject().put("type", "string").put("value", value)
        is Set<*> -> if (value.all { it is String }) {
            JSONObject().put("type", "stringSet")
                .put("value", JSONArray(value.filterIsInstance<String>().sorted()))
        } else {
            unsupported(value)
        }
        else -> unsupported(value)
    }

    fun save(preferences: SharedPreferences, request: JSONObject): JSONObject {
        val response = response()
        val changes = request.optJSONArray("changes")
            ?: return response.put("status", "failed").put("message", "Missing changes")
        if (changes.length() == 0) {
            return response.put("status", "failed").put("message", "No changes")
        }

        val originals = try {
            preferences.all
        } catch (_: Exception) {
            return response.put("status", "failed").put("message", "Store could not be read")
        }
        val proposed = linkedMapOf<String, Any>()
        val seen = mutableSetOf<String>()
        val conflicts = JSONArray()

        try {
            for (index in 0 until changes.length()) {
                val change = changes.getJSONObject(index)
                val key = change.getString("key")
                if (!seen.add(key)) throw IllegalArgumentException("Duplicate key")
                val current = originals[key]
                val before = change.getJSONObject("before")
                val after = change.getJSONObject("after")
                val beforeType = before.getString("type")
                val afterType = after.getString("type")
                if (beforeType != afterType || beforeType == "unsupported") {
                    throw IllegalArgumentException("Type changed")
                }
                if (!originals.containsKey(key) || !equalTyped(current, before)) {
                    conflicts.put(
                        JSONObject().put("key", key).put(
                            "current",
                            if (current == null) JSONObject.NULL else typed(current),
                        ),
                    )
                    continue
                }
                proposed[key] = decoded(after)
            }
        } catch (_: Exception) {
            return response.put("status", "failed").put("message", "Invalid key, type, or value")
        }

        if (conflicts.length() > 0) {
            return response.put("status", "conflict").put("conflicts", conflicts)
        }

        return try {
            val editor = preferences.edit()
            for ((key, value) in proposed) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
            }
            if (editor.commit()) response.put("status", "ok")
            else response.put("status", "failed").put("message", "SharedPreferences commit failed")
        } catch (_: Exception) {
            response.put("status", "failed").put("message", "SharedPreferences commit failed")
        }
    }

    private fun equalTyped(current: Any?, expected: JSONObject): Boolean = try {
        typed(current).getString("type") == expected.getString("type") &&
            current == decoded(expected)
    } catch (_: Exception) {
        false
    }

    private fun decoded(value: JSONObject): Any = when (value.getString("type")) {
        "boolean" -> value.getBoolean("value")
        "int" -> value.get("value").toString().toInt()
        "long" -> value.getString("value").toLong()
        "float" -> value.getString("value").toFloat().also { require(it.isFinite()) }
        "string" -> value.getString("value")
        "stringSet" -> value.getJSONArray("value").let { array ->
            (0 until array.length()).map { array.getString(it) }.toSet()
        }
        else -> throw IllegalArgumentException("Unsupported type")
    }

    private fun response(): JSONObject = JSONObject().put("version", BridgeProtocol.VERSION)

    private fun unsupported(value: Any?): JSONObject = JSONObject()
        .put("type", "unsupported")
        .put("value", value?.toString() ?: "null")
}
