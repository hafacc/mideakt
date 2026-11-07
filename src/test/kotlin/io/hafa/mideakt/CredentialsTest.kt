package io.hafa.mideakt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CredentialsTest {
    private val device = DiscoveredDevice(1, "10.0.0.7", 6444, 3, "ac", null)

    @Test
    fun equalByContentWithoutPrintingSecrets() {
        val credentials = DeviceCredentials(device, byteArrayOf(1, 2), byteArrayOf(3))
        assertEquals(credentials, DeviceCredentials(device, byteArrayOf(1, 2), byteArrayOf(3)))
        assertEquals(credentials.hashCode(), DeviceCredentials(device, byteArrayOf(1, 2), byteArrayOf(3)).hashCode())
        assertFalse(credentials == DeviceCredentials(device, byteArrayOf(1, 2), byteArrayOf(4)))
        assertFalse("token" in credentials.toString())
    }
}
