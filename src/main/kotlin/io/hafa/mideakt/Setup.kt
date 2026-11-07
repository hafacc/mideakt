package io.hafa.mideakt

/** Thrown when [Setup.run] cannot complete, e.g. no devices are found on the LAN. */
class SetupException(message: String) : Exception(message)

/**
 * One-shot setup: discover devices, fetch each V3 device's token/key from the
 * cloud, verify by authenticating locally, and return credentials. The only step
 * that contacts the cloud.
 *
 * Version-2 devices need no cloud token; they are verified with a local query and
 * only returned if they respond.
 *
 * Takes the Midea (MSmartHome) account the devices are registered to: the cloud
 * only issues keys for the signed-in account's own devices.
 *
 * ```kotlin
 * val credentials = Setup.run("you@example.com", "password").first()
 * ```
 *
 * Store the returned [DeviceCredentials] and hand them to [MideaClient]; setup only
 * needs to run again if a device is re-provisioned. To find devices that ignore
 * broadcasts, or over a particular network, run [Discovery.discover] yourself and pass
 * its result as `devices`.
 */
object Setup {
    /** Runs setup on [devices] with their owner's [account]; see [Setup]. */
    fun run(
        account: String,
        password: String,
        devices: List<DiscoveredDevice> = Discovery.discover(),
    ): List<DeviceCredentials> = run(SmartHomeCloud(account, password), devices)

    /** Runs setup on [devices] against [cloud], signing in only if one is version 3; see [Setup]. */
    fun run(cloud: SmartHomeCloud, devices: List<DiscoveredDevice> = Discovery.discover()): List<DeviceCredentials> {
        if (devices.isEmpty()) throw SetupException("No devices found")

        val session = if (devices.any { it.version >= 3 }) cloud.login() else null

        val outcomes = devices.map { device -> device to runCatching { credentials(device, session) } }
        val results = outcomes.mapNotNull { (_, outcome) -> outcome.getOrNull() }
        if (results.isEmpty()) throw provisioningFailure(outcomes)
        return results
    }

    /** Builds the all-failed error, naming each device and the reason it couldn't be provisioned. */
    private fun provisioningFailure(
        outcomes: List<Pair<DiscoveredDevice, Result<DeviceCredentials>>>,
    ): SetupException {
        val summary = outcomes.joinToString("; ") { (device, outcome) ->
            val cause = outcome.exceptionOrNull()
            "${device.name} (v${device.version}): ${cause?.message ?: cause?.javaClass?.simpleName ?: "unknown"}"
        }
        return SetupException("Devices found, but none could be provisioned: $summary").apply {
            outcomes.forEach { (_, outcome) -> outcome.exceptionOrNull()?.let { addSuppressed(it) } }
        }
    }

    /**
     * Credentials for one [device], checked by querying the device with them.
     *
     * A version-3 device's key comes from the cloud through [session]. The cloud may file it
     * under either byte order of the device id and answer for both, so this fetches both
     * pairs and returns the one the device accepts. A version-2 device needs no [session].
     * Throws the error that best explains a failure: the device refusing a key, then
     * [DeviceNotInAccountException], then any other cloud error.
     */
    @Suppress("TooGenericExceptionCaught")
    fun credentials(device: DiscoveredDevice, session: SmartHomeSession? = null): DeviceCredentials {
        if (device.version < 3) return checked(DeviceCredentials(device))
        requireNotNull(session) { "A version-3 device needs a session from SmartHomeCloud.login()" }
        var cloudError: Exception? = null
        var checkError: Exception? = null
        for (bigEndian in listOf(false, true)) {
            val pair = try {
                session.cloud.getToken(session, device.id, bigEndian)
            } catch (e: DeviceNotInAccountException) {
                // Not being the owner explains a failure better than a missing key does.
                cloudError = e
                null
            } catch (e: Exception) {
                if (cloudError == null) cloudError = e
                null
            }
            if (pair != null) {
                try {
                    return checked(DeviceCredentials(device, pair.first, pair.second))
                } catch (e: Exception) {
                    checkError = e
                }
            }
        }
        // A key the device turned down says more than a byte order the cloud didn't know.
        throw checkError ?: cloudError!!
    }

    private fun checked(credentials: DeviceCredentials): DeviceCredentials {
        MideaClient(credentials).use { it.refresh() }
        return credentials
    }
}
