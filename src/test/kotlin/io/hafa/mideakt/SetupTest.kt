package io.hafa.mideakt

import java.net.SocketTimeoutException
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

    /** A rejected pair yields to a cloud error, since the other pair never got checked. */
    @Test
    fun cloudErrorBeatsRejection() {
        val cloudError = CloudException("no key", 1)
        assertEquals(cloudError, Setup.credentialsError(null, cloudError, HandshakeRejectedException("no"), null))
    }

    /** A rejected pair yields to a timed-out check of the other pair. */
    @Test
    fun checkErrorBeatsRejection() {
        val timeout = SocketTimeoutException("slow")
        assertEquals(timeout, Setup.credentialsError(timeout, null, HandshakeRejectedException("no"), null))
    }

    /** A failed check of a returned pair says more than a fetch that failed. */
    @Test
    fun checkErrorBeatsCloudError() {
        val timeout = SocketTimeoutException("slow")
        assertEquals(timeout, Setup.credentialsError(timeout, CloudException("no key", 1), null, null))
    }

    /** With every returned pair rejected and no fetch failing, the rejection stands. */
    @Test
    fun rejectionWhenEveryPairRejected() {
        val rejection = HandshakeRejectedException("no")
        assertEquals(rejection, Setup.credentialsError(null, null, rejection, null))
    }

    /** A pair the cloud doesn't have for one byte order doesn't hide a rejection of the other. */
    @Test
    fun rejectionBeatsMissingPair() {
        val rejection = HandshakeRejectedException("no")
        assertEquals(rejection, Setup.credentialsError(null, null, rejection, KeyNotFoundException("none")))
    }

    /** With no pair for either byte order, the missing pair is reported. */
    @Test
    fun missingPairWhenNoneReturned() {
        val missing = KeyNotFoundException("none")
        assertEquals(missing, Setup.credentialsError(null, null, null, missing))
    }

    /** A pair returned for one byte order means the account owns the device, so its rejection stands. */
    @Test
    fun rejectionBeatsNotInAccount() {
        val rejection = HandshakeRejectedException("no")
        assertEquals(rejection, Setup.credentialsError(null, DeviceNotInAccountException("no"), rejection, null))
    }

    /** With no pair returned, not being in the account stands. */
    @Test
    fun notInAccountWithoutPair() {
        val notInAccount = DeviceNotInAccountException("no")
        assertEquals(notInAccount, Setup.credentialsError(null, notInAccount, null, KeyNotFoundException("none")))
    }
}
