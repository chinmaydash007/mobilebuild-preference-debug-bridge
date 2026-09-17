package io.github.chinmaydash007.mobilebuild.preferences

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal object BridgeProtocol {
    const val VERSION = 1
    const val MAX_REQUEST_BYTES = 1_048_576
    const val MAX_REQUESTS = 64
    const val RESULT_TTL_MILLIS = 120_000L
}

internal object BridgeAccessPolicy {
    fun isAllowed(applicationFlags: Int, callingUid: Int): Boolean =
        applicationFlags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && callingUid == Process.SHELL_UID
}

internal object BridgeIO {
    fun readLimited(input: InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(8192)
        val output = ByteArrayOutputStream()
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) {
                throw IOException("Preference request too large")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

internal object PreferenceStoreWire {
    fun descriptor(context: Context, store: RegisteredPreferenceStore): JSONObject {
        val entry = JSONObject()
            .put("id", store.id)
            .put("label", store.label)
            .put("kind", "registered")
        return try {
            store.open(context).all
            entry.put("available", true)
        } catch (_: Exception) {
            entry.put("available", false)
                .put("message", "Store could not be opened with its registered configuration")
        }
    }
}

internal class SaveResultStore(
    private val maximumRequests: Int = BridgeProtocol.MAX_REQUESTS,
    private val timeToLiveMillis: Long = BridgeProtocol.RESULT_TTL_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val responses = linkedMapOf<String, JSONObject>()
    private val usedRequests = linkedMapOf<String, Long>()

    @Synchronized
    fun begin(requestID: String): Boolean {
        cleanup()
        if (usedRequests.size >= maximumRequests || requestID in usedRequests) return false
        usedRequests[requestID] = now()
        responses[requestID] = pending()
        return true
    }

    @Synchronized
    fun complete(requestID: String, response: JSONObject) {
        if (requestID in usedRequests) responses[requestID] = response
    }

    @Synchronized
    fun read(requestID: String): JSONObject {
        cleanup()
        val response = responses[requestID] ?: pending()
        if (response.optString("status") != "pending") responses.remove(requestID)
        return response
    }

    @Synchronized
    fun cleanup() {
        val cutoff = now() - timeToLiveMillis
        val expired = usedRequests.filterValues { it < cutoff }.keys
        expired.forEach {
            usedRequests.remove(it)
            responses.remove(it)
        }
    }

    private fun pending(): JSONObject = JSONObject()
        .put("version", BridgeProtocol.VERSION)
        .put("status", "pending")
}
