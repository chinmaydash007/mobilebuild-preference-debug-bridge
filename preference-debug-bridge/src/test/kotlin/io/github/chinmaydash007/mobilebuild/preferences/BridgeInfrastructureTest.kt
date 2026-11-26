package io.github.chinmaydash007.mobilebuild.preferences

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.IOException

class BridgeInfrastructureTest {
    @Test
    fun registryRejectsBlankAndDuplicateStoresWithoutOpeningFactories() {
        val registry = PreferenceStoreRegistry()
        var opens = 0
        registry.register("settings", "Settings") {
            opens++
            MemoryPreferences(mutableMapOf())
        }

        assertEquals(0, opens)
        assertEquals(listOf("settings"), registry.stores().map { it.id })
        assertThrows(IllegalArgumentException::class.java) {
            registry.register("settings", "Duplicate") { MemoryPreferences(mutableMapOf()) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(" ", "Blank") { MemoryPreferences(mutableMapOf()) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.register("other", " ") { MemoryPreferences(mutableMapOf()) }
        }
    }

    @Test
    fun factoryFailuresBecomeGenericUnavailableStores() {
        val registry = PreferenceStoreRegistry()
        registry.register("secure", "Secure") { throw IllegalStateException("secret key alias") }

        val descriptor = PreferenceStoreWire.descriptor(mock(Context::class.java), registry.stores().single())

        assertFalse(descriptor.getBoolean("available"))
        assertFalse(descriptor.getString("message").contains("secret"))
    }

    @Test
    fun accessRequiresBothDebuggableApplicationAndShellUid() {
        assertTrue(BridgeAccessPolicy.isAllowed(ApplicationInfo.FLAG_DEBUGGABLE, Process.SHELL_UID))
        assertFalse(BridgeAccessPolicy.isAllowed(0, Process.SHELL_UID))
        assertFalse(BridgeAccessPolicy.isAllowed(ApplicationInfo.FLAG_DEBUGGABLE, 10_000))
    }

    @Test
    fun hostEnablementIsEvaluatedEveryTimeAndFailsClosed() {
        var enabled = false

        assertFalse(BridgeAccessPolicy.hostAllows { enabled })
        enabled = true
        assertTrue(BridgeAccessPolicy.hostAllows { enabled })
        assertFalse(BridgeAccessPolicy.hostAllows { throw IllegalStateException("fixture") })
    }

    @Test
    fun providerDefaultsToDebuggableAndAllowsAnExplicitHostDecision() {
        val context = mock(Context::class.java)
        val applicationInfo = ApplicationInfo()
        `when`(context.applicationInfo).thenReturn(applicationInfo)
        val defaultProvider = AccessFixtureProvider()

        applicationInfo.flags = 0
        assertFalse(defaultProvider.enabled(context))
        applicationInfo.flags = ApplicationInfo.FLAG_DEBUGGABLE
        assertTrue(defaultProvider.enabled(context))

        applicationInfo.flags = 0
        assertTrue(AccessFixtureProvider(true).enabled(context))
        applicationInfo.flags = ApplicationInfo.FLAG_DEBUGGABLE
        assertFalse(AccessFixtureProvider(false).enabled(context))
    }

    @Test
    fun boundedInputPreservesBytesAndRejectsOversizedRequests() {
        val bytes = byteArrayOf(0, 1, 2, 127, -1)
        assertTrue(bytes.contentEquals(BridgeIO.readLimited(ByteArrayInputStream(bytes), bytes.size)))
        assertThrows(IOException::class.java) {
            BridgeIO.readLimited(ByteArrayInputStream(bytes), bytes.size - 1)
        }
    }

    @Test
    fun saveResultsRejectDuplicatesEnforceCapacityAndExpire() {
        var now = 1_000L
        val results = SaveResultStore(maximumRequests = 2, timeToLiveMillis = 100L) { now }
        assertTrue(results.begin("one"))
        assertFalse(results.begin("one"))
        assertTrue(results.begin("two"))
        assertFalse(results.begin("three"))

        results.complete("one", JSONObject().put("version", 1).put("status", "ok"))
        assertEquals("ok", results.read("one").getString("status"))
        assertEquals("pending", results.read("one").getString("status"))

        now = 1_101L
        assertTrue(results.begin("three"))
        assertEquals("pending", results.read("two").getString("status"))
    }
}

private class AccessFixtureProvider(
    private val explicitDecision: Boolean? = null,
) : PreferenceDebugProvider() {
    override fun registerStores(registry: PreferenceStoreRegistry) = Unit

    override fun isPreferenceInspectionEnabled(context: Context): Boolean =
        explicitDecision ?: super.isPreferenceInspectionEnabled(context)

    fun enabled(context: Context): Boolean = isPreferenceInspectionEnabled(context)
}
