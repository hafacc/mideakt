package io.hafa.mideakt

import java.util.concurrent.Executors
import javax.net.SocketFactory

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
 * its result as `devices`. `socketFactory` makes the sockets for the local checks, as in
 * [credentials].
 *
 * On Android, discovery and the local checks must use the Wi-Fi:
 *
 * ```kotlin
 * val devices = Discovery.discover(bindSocket = wifi::bindSocket)
 * val credentials = Setup.run("you@example.com", "password", devices, wifi.socketFactory)
 * ```
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
        socketFactory: SocketFactory = SocketFactory.getDefault(),
    ): List<DeviceCredentials> = run(SmartHomeCloud(account, password), devices, socketFactory)

    /** Runs setup on [devices] against [cloud], signing in only if one is version 3; see [Setup]. */
    fun run(
        cloud: SmartHomeCloud,
        devices: List<DiscoveredDevice> = Discovery.discover(),
        socketFactory: SocketFactory = SocketFactory.getDefault(),
    ): List<DeviceCredentials> = provisionAll(cloud, devices, socketFactory)

    /**
     * Runs setup on version-2 [devices], which need no account; a version-3 device among them
     * fails with [AccountRequiredException]. See [Setup].
     */
    fun run(
        devices: List<DiscoveredDevice> = Discovery.discover(),
        socketFactory: SocketFactory = SocketFactory.getDefault(),
    ): List<DeviceCredentials> = provisionAll(null, devices, socketFactory)

    private fun provisionAll(
        cloud: SmartHomeCloud?,
        devices: List<DiscoveredDevice>,
        socketFactory: SocketFactory,
    ): List<DeviceCredentials> {
        if (devices.isEmpty()) throw SetupException("No devices found")
        val session = if (cloud != null && devices.any { it.version >= 3 }) cloud.login() else null

        // Each device's key fetch and local check is independent, so they run side by side.
        val pool = Executors.newFixedThreadPool(devices.size)
        val outcomes = try {
            devices.map { device ->
                pool.submit<Result<DeviceCredentials>> { runCatching { credentials(device, session, socketFactory) } }
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
     * a version-3 device without one throws [AccountRequiredException].
     *
     * Otherwise it throws the error that best explains a failure, in this order:
     * - a local check error other than a rejection, such as a timeout or a refused connection;
     * - a cloud error from a fetch, [DeviceNotInAccountException] first;
     * - [HandshakeRejectedException], which also beats [DeviceNotInAccountException];
     * - a [CloudException] saying the cloud has no pair for either byte order.
     *
     * [socketFactory] makes the sockets for the local check only; the cloud requests use the
     * default network. On Android, pass a Wi-Fi `Network.socketFactory` so the check reaches
     * the device while the cloud requests may use mobile data.
     */
    @Suppress("TooGenericExceptionCaught")
    fun credentials(
        device: DiscoveredDevice,
        session: SmartHomeSession? = null,
        socketFactory: SocketFactory = SocketFactory.getDefault(),
    ): DeviceCredentials {
        if (device.version < 3) return checked(DeviceCredentials(device), socketFactory)
        if (session == null) throw AccountRequiredException()
        var cloudError: Exception? = null
        var checkError: Exception? = null
        var rejection: Exception? = null
        var missing: Exception? = null
        for (bigEndian in listOf(false, true)) {
            val pair = try {
                session.cloud.getToken(session, device.id, bigEndian)
            } catch (e: KeyNotFoundException) {
                missing = e
                null
            } catch (e: DeviceNotInAccountException) {
                cloudError = e
                null
            } catch (e: Exception) {
                if (cloudError == null) cloudError = e
                null
            }
            if (pair != null) {
                try {
                    return checked(DeviceCredentials(device, pair.first, pair.second), socketFactory)
                } catch (e: HandshakeRejectedException) {
                    rejection = e
                } catch (e: Exception) {
                    checkError = e
                }
            }
        }
        throw credentialsError(checkError, cloudError, rejection, missing)
    }

    /**
     * The error [credentials] throws, given the last non-rejection [checkError], the most
     * telling [cloudError], the last [rejection] and the last [missing] pair; at least one is set.
     */
    internal fun credentialsError(
        checkError: Exception?,
        cloudError: Exception?,
        rejection: Exception?,
        missing: Exception?,
    ): Exception = checkError
        ?: cloudError?.takeUnless { it is DeviceNotInAccountException && rejection != null }
        ?: rejection
        ?: missing!!

    private fun checked(credentials: DeviceCredentials, socketFactory: SocketFactory): DeviceCredentials {
        MideaClient(credentials, socketFactory = socketFactory).use { it.refresh() }
        return credentials
    }
}

private fun summary(failures: List<SetupFailure>): String = failures.joinToString("; ") { (device, error) ->
    "${device.name} (v${device.version}): ${error.message ?: error.javaClass.simpleName}"
}
