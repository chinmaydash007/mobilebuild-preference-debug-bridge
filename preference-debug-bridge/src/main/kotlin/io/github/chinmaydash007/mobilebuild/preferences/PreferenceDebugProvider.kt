package io.github.chinmaydash007.mobilebuild.preferences

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.util.UUID

/**
 * Debug-only content provider used by Mobile Build to inspect registered SharedPreferences.
 *
 * The library does not add this provider to an application manifest. A consumer must subclass it
 * from `src/debug`, register only the intended stores, and declare that subclass in the debug
 * manifest with `android.permission.DUMP`.
 */
abstract class PreferenceDebugProvider : ContentProvider() {
    private val registry = PreferenceStoreRegistry()
    private val results = SaveResultStore()
    private val saveLock = Any()

    /** Registers the stores exposed by this application's debug build. */
    protected abstract fun registerStores(registry: PreferenceStoreRegistry)

    final override fun onCreate(): Boolean {
        if (context == null) return false
        registerStores(registry)
        return true
    }

    final override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        checkCaller()
        results.cleanup()
        val parts = uri.pathSegments

        if (mode == "r") {
            val payload = when {
                parts == listOf("v1", "handshake") -> handshake()
                parts == listOf("v1", "stores") -> listStores()
                parts.size == 3 && parts[0] == "v1" && parts[1] == "store" ->
                    snapshot(decodeID(parts[2]))
                parts.size == 3 && parts[0] == "v1" && parts[1] == "result" ->
                    results.read(parts[2])
                else -> throw FileNotFoundException("Unknown preference request")
            }
            return outputPipe(payload)
        }

        if (mode == "w" && parts.size == 3 && parts[0] == "v1" && parts[1] == "save") {
            val requestID = parts[2]
            try {
                UUID.fromString(requestID)
            } catch (_: IllegalArgumentException) {
                throw FileNotFoundException("Invalid request ID")
            }
            if (!results.begin(requestID)) {
                throw FileNotFoundException("Duplicate or excessive preference requests")
            }
            return inputPipe(requestID)
        }

        throw FileNotFoundException("Unknown preference request")
    }

    private fun checkCaller() {
        val app = context ?: throw SecurityException("No app context")
        val callingUID = Binder.getCallingUid()
        if (!BridgeAccessPolicy.isDebuggable(app.applicationInfo.flags)) {
            throw SecurityException("Preference inspection requires a debuggable app")
        }
        if (!BridgeAccessPolicy.isShell(callingUID)) {
            throw SecurityException("Preference inspection requires an ADB shell (caller UID $callingUID)")
        }
    }

    private fun outputPipe(payload: JSONObject): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        Thread({
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
        }, "MobileBuildPreferenceRead").apply {
            isDaemon = true
            start()
        }
        return pipe[0]
    }

    private fun inputPipe(requestID: String): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        Thread({
            val result = try {
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { input ->
                    BridgeIO.readLimited(input, BridgeProtocol.MAX_REQUEST_BYTES)
                }
                save(JSONObject(String(bytes, Charsets.UTF_8)))
            } catch (_: Exception) {
                failed("Could not validate or save the preference request")
            }
            results.complete(requestID, result)
        }, "MobileBuildPreferenceWrite").apply {
            isDaemon = true
            start()
        }
        return pipe[1]
    }

    private fun handshake(): JSONObject {
        val app = appContext()
        return JSONObject()
            .put("version", BridgeProtocol.VERSION)
            .put("packageID", app.packageName)
            .put("label", app.packageManager.getApplicationLabel(app.applicationInfo).toString())
    }

    private fun listStores(): JSONObject {
        val app = appContext()
        val entries = JSONArray()
        registry.stores().forEach { store ->
            entries.put(PreferenceStoreWire.descriptor(app, store))
        }
        return JSONObject().put("version", BridgeProtocol.VERSION).put("stores", entries)
    }

    private fun snapshot(id: String): JSONObject {
        val app = appContext()
        val store = registry.stores().firstOrNull { it.id == id }
            ?: throw FileNotFoundException("Unknown store")
        val values = try {
            store.open(app).all
        } catch (_: Exception) {
            throw FileNotFoundException("Store could not be opened")
        }
        val entries = JSONArray()
        values.toSortedMap().forEach { (key, value) ->
            entries.put(JSONObject().put("key", key).put("value", PreferenceTransaction.typed(value)))
        }
        return JSONObject()
            .put("version", BridgeProtocol.VERSION)
            .put("storeID", id)
            .put("entries", entries)
    }

    private fun save(request: JSONObject): JSONObject = synchronized(saveLock) {
        if (request.optInt("version") != BridgeProtocol.VERSION) {
            return@synchronized failed("Unsupported protocol version")
        }
        val storeID = request.optString("storeID")
        val store = registry.stores().firstOrNull { it.id == storeID }
            ?: return@synchronized failed("Store no longer exists")
        val preferences = try {
            store.open(appContext())
        } catch (_: Exception) {
            return@synchronized failed("Store could not be opened")
        }
        PreferenceTransaction.save(preferences, request)
    }

    private fun decodeID(segment: String): String = try {
        String(
            Base64.decode(segment, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
            Charsets.UTF_8,
        )
    } catch (_: IllegalArgumentException) {
        throw FileNotFoundException("Invalid store ID")
    }

    private fun failed(message: String): JSONObject = JSONObject()
        .put("version", BridgeProtocol.VERSION)
        .put("status", "failed")
        .put("message", message)

    private fun appContext(): Context = context ?: throw FileNotFoundException("No app context")

    final override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    final override fun getType(uri: Uri): String? = null
    final override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    final override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    final override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
