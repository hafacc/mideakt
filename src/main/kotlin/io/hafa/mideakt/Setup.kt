package io.hafa.mideakt

import java.util.concurrent.Executors

/**
 * Thrown when [Setup.run] can't set up any device; [failures] holds each device it tried
 * and the error that stopped it.
 */
open class SetupException(
    message: String,
    /** Each device setup tried, with the error that stopped it; empty if none were found. */
    val failures: List<SetupFailure> = emptyList(),
) : Exception(message)

/**
 * Thrown when no device could be set up and the cloud reported at least one of them as not
 * registered to the given account.
 */
class NotInAccountException(failures: List<SetupFailure>) :
    SetupException("The cloud says these devices aren't in this account: ${summary(failures)}", failures)

/** A version-3 device's error when setup ran without an account to fetch its key with. */
class AccountRequiredException : Exception("A version-3 device needs a cloud key; run setup with an account")

/** A discovered device that setup couldn't set up, and why. */
data class SetupFailure(
    /** The device as discovered. */
    val device: DiscoveredDevice,
    /**
     * Why it failed: [AccountRequiredException], a [CloudException] from the key fetch, or the
     * error from the local check, such as [HandshakeRejectedException].
     */
    val error: Throwable,
)

/**
 * One-shot setup: discover devices, fetch each V3 device's token/key from the
 * cloud, verify every device with a local query, and return credentials. The only
 * step that contacts the cloud.
 *
 * Takes the Midea (MSmartHome) account the devices are registered to: the cloud
 * only issues keys for the signed-in account's own devices. Version-2 devices need
 * no cloud token, so a home with only those can call `run()` with no account.
 *
 * ```kotlin
 * val credentials = Setup.run("you@example.com", "password").first()
 * ```
 *
 * Store the returned [DeviceCredentials] and hand them to [MideaClient]; setup only
 * needs to run again if a device is re-provisioned. To find devices that ignore
 * broadcasts, or over a particular network, run [Discovery.discover] yourself and pass
 * its result as `devices`.
 *
 * It returns credentials for every device it could set up, leaving out the rest. When
 * none could be set up it throws [NotInAccountException] if the cloud said a device
 * isn't in the account, and [SetupException] otherwise; both list each device's error.
 */
object Setup {
    /**
     * Runs setup on [devices] with their owner's [account]; see [Setup]. Throws
     * [CloudLoginException] if the cloud rejects the account.
     */
    fun run(
        account: String,
        password: String,
        devices: List<DiscoveredDevice> = Discovery.discover(),
    ): List<DeviceCredentials> = run(SmartHomeCloud(account, password), devices)

    /** Runs setup on [devices] against [cloud], signing in only if one is version 3; see [Setup]. */
    fun run(cloud: SmartHomeCloud, devices: List<DiscoveredDevice> = Discovery.discover()): List<DeviceCredentials> =
        provisionAll(cloud, devices)

    /**
     * Runs setup on version-2 [devices], which need no account; a version-3 device among them
     * fails with [AccountRequiredException]. See [Setup].
     */
    fun run(devices: List<DiscoveredDevice> = Discovery.discover()): List<DeviceCredentials> =
        provisionAll(null, devices)

    private fun provisionAll(cloud: SmartHomeCloud?, devices: List<DiscoveredDevice>): List<DeviceCredentials> {
        if (devices.isEmpty()) throw SetupException("No devices found")
        val session = if (cloud != null && devices.any { it.version >= 3 }) cloud.login() else null

        // Each device's key fetch and local check is independent, so they run side by side.
        val pool = Executors.newFixedThreadPool(devices.size)
        val outcomes = try {
            devices.map { device ->
                pool.submit<Result<DeviceCredentials>> { runCatching { credentials(device, session) } }
            }
                .map { it.get() }
        } finally {
            pool.shutdown()
        }

        val results = outcomes.mapNotNull { it.getOrNull() }
        if (results.isNotEmpty()) return results
        val failures = devices.zip(outcomes) { device, outcome -> SetupFailure(device, outcome.exceptionOrNull()!!) }
        throw if (failures.any { it.error is DeviceNotInAccountException }) {
            NotInAccountException(failures)
        } else {
            SetupException("Devices found, but none could be set up: ${summary(failures)}", failures)
        }
    }

    /**
     * Credentials for one [device], checked by querying the device with them.
     *
     * A version-3 device's key comes from the cloud through [session]. The cloud may file it
     * under either byte order of the device id and answer for both, so this fetches both
     * pairs and returns the one the device accepts. A version-2 device needs no [session];
     * a version-3 device without one throws [AccountRequiredException]. Otherwise it throws
     * the error that best explains a failure: the device refusing a key, then
     * [DeviceNotInAccountException], then any other cloud error.
     */
    @Suppress("TooGenericExceptionCaught")
    fun credentials(device: DiscoveredDevice, session: SmartHomeSession? = null): DeviceCredentials {
        if (device.version < 3) return checked(DeviceCredentials(device))
        if (session == null) throw AccountRequiredException()
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

private fun summary(failures: List<SetupFailure>): String = failures.joinToString("; ") { (device, error) ->
    "${device.name} (v${device.version}): ${error.message ?: error.javaClass.simpleName}"
}
