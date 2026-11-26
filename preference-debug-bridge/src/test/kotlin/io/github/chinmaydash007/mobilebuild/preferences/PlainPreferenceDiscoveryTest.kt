package io.github.chinmaydash007.mobilebuild.preferences

import android.content.Context
import android.content.pm.ApplicationInfo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Files

class PlainPreferenceDiscoveryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun discoveryIsDisabledByDefaultAndRefreshesFilesAfterOptIn() {
        val appData = temporary.newFolder("app-data")
        val sharedPreferences = File(appData, "shared_prefs").apply { mkdirs() }
        file(sharedPreferences, "explicit.xml")
        file(sharedPreferences, "ignored.xml")
        file(sharedPreferences, "zeta.xml")
        file(sharedPreferences, "plain.xml")
        file(sharedPreferences, "notes.txt")
        File(sharedPreferences, "directory.xml").mkdir()

        val values = mutableMapOf<String, MutableMap<String, Any>>()
        val context = context(appData, values)
        val registry = PreferenceStoreRegistry()
        registry.register("explicit", "Explicit") { MemoryPreferences(mutableMapOf("flag" to true)) }

        assertEquals(listOf("explicit"), registry.stores(context).map { it.id })

        registry.discoverPlainPreferences(setOf("ignored.xml"))
        assertEquals(
            listOf("explicit", "plain", "zeta"),
            registry.stores(context).map { it.id },
        )

        registry.discoverPlainPreferences(setOf("zeta"))
        file(sharedPreferences, "created_later.xml")
        assertEquals(
            listOf("explicit", "created_later", "plain"),
            registry.stores(context).map { it.id },
        )

        File(sharedPreferences, "plain.xml").delete()
        assertEquals(
            listOf("explicit", "created_later"),
            registry.stores(context).map { it.id },
        )
    }

    @Test
    fun discoveredPlainStoreIsEditableThroughTheExistingTransaction() {
        val appData = temporary.newFolder("editable-app")
        val sharedPreferences = File(appData, "shared_prefs").apply { mkdirs() }
        file(sharedPreferences, "discovered.xml")
        val values = mutableMapOf(
            "discovered" to mutableMapOf<String, Any>("enabled" to false),
        )
        val context = context(appData, values)
        val registry = PreferenceStoreRegistry().apply { discoverPlainPreferences() }
        val store = registry.stores(context).single()
        val request = JSONObject()
            .put("version", BridgeProtocol.VERSION)
            .put("storeID", "discovered")
            .put(
                "changes",
                JSONArray().put(
                    JSONObject()
                        .put("key", "enabled")
                        .put("before", PreferenceTransaction.typed(false))
                        .put("after", PreferenceTransaction.typed(true)),
                ),
            )

        assertEquals("plain", store.kind)
        assertEquals(
            "ok",
            PreferenceTransaction.save(store.resolve(context).preferences, request).getString("status"),
        )
        assertEquals(true, values.getValue("discovered")["enabled"])
    }

    @Test
    fun suspectedEncryptedFilesAreUnavailableUnlessExplicitlyRegistered() {
        val appData = temporary.newFolder("encrypted-app")
        val sharedPreferences = File(appData, "shared_prefs").apply { mkdirs() }
        file(sharedPreferences, "secret_encrypted.xml")
        file(sharedPreferences, "opaque.xml")
        val values = mutableMapOf(
            "secret_encrypted" to mutableMapOf<String, Any>("ciphertext" to "opaque"),
            "opaque" to mutableMapOf<String, Any>(
                "__androidx_security_crypto_encrypted_prefs_key_keyset__" to "opaque",
            ),
        )
        val context = context(appData, values)
        val registry = PreferenceStoreRegistry().apply { discoverPlainPreferences() }

        val descriptors = registry.stores(context).associate { store ->
            store.id to PreferenceStoreWire.descriptor(context, store)
        }
        descriptors.values.forEach { descriptor ->
            assertEquals("unregisteredEncrypted", descriptor.getString("kind"))
            assertFalse(descriptor.getBoolean("available"))
            assertTrue(descriptor.getString("message").contains("Register this encrypted store"))
        }
        registry.stores(context).forEach { store ->
            assertThrows(PreferenceStoreUnavailable::class.java) { store.resolve(context) }
        }

        registry.register("secret_encrypted", "Secure") {
            MemoryPreferences(mutableMapOf("decrypted" to true))
        }
        val secure = registry.stores(context).first { it.id == "secret_encrypted" }
        val descriptor = PreferenceStoreWire.descriptor(context, secure)
        assertEquals("registered", descriptor.getString("kind"))
        assertTrue(descriptor.getBoolean("available"))
        assertEquals(1, registry.stores(context).count { it.id == "secret_encrypted" })
    }

    @Test
    fun discoveryRejectsUnsafeExclusionsAndSymlinkedFiles() {
        val root = temporary.newFolder("shared_prefs")
        file(root, "plain.xml")
        val outside = file(temporary.root, "outside.xml")
        try {
            Files.createSymbolicLink(File(root, "linked.xml").toPath(), outside.toPath())
        } catch (exception: Exception) {
            assumeNoException(exception)
        }

        assertEquals(listOf("plain"), PlainPreferenceDiscovery.fileNames(root, emptySet()))
        assertThrows(IllegalArgumentException::class.java) {
            PreferenceStoreRegistry().discoverPlainPreferences(setOf(" "))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PreferenceStoreRegistry().discoverPlainPreferences(setOf("nested/store"))
        }
    }

    private fun context(
        appData: File,
        values: MutableMap<String, MutableMap<String, Any>>,
    ): Context {
        val context = mock(Context::class.java)
        val applicationInfo = ApplicationInfo().apply { dataDir = appData.path }
        `when`(context.applicationInfo).thenReturn(applicationInfo)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenAnswer { invocation ->
            val name = invocation.getArgument<String>(0)
            MemoryPreferences(values.getOrPut(name) { mutableMapOf() })
        }
        return context
    }

    private fun file(parent: File, name: String): File = File(parent, name).apply {
        writeText("<map />")
    }
}
