package io.hafa.mideakt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SetupTest {
    private val device = DiscoveredDevice(1, "10.0.0.7", 6444, 3, "ac", null)

    @Test
    fun reportsNoDevices() {
        val error = assertFailsWith<SetupException> { Setup.run(devices = emptyList()) }
        assertTrue(error.failures.isEmpty())
    }

    /** Without an account, a version-3 device fails on its own and the failure names it. */
    @Test
    fun versionThreeNeedsAnAccount() {
        val error = assertFailsWith<SetupException> { Setup.run(devices = listOf(device)) }
        assertEquals(listOf(device), error.failures.map { it.device })
        assertIs<AccountRequiredException>(error.failures.single().error)
    }
}
