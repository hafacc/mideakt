package io.hafa.mideakt

/**
 * Addressing plus the authentication secrets for one device — everything [MideaClient]
 * needs to connect. Normally obtained from [Setup]; build it from a [DiscoveredDevice]
 * when fetching the key yourself.
 */
data class DeviceCredentials(
    /** The device's user-assigned name, from discovery. */
    val name: String,
    /** The device id from discovery (≤ 2^48), used to address and authenticate it. */
    val id: Long,
    /** The device's IP address on the LAN. */
    val ip: String,
    /** The device's TCP control port. */
    val port: Int,
    /** The device's protocol version (3 for the token/key handshake; 2 needs no key). */
    val version: Int,
    /** The authentication token as a hex string; empty for non-V3 devices. */
    val token: String,
    /** The authentication key as a hex string; empty for non-V3 devices. */
    val key: String,
    /** The device's serial number, from discovery; null if the device reported none. */
    val serialNumber: String?,
) {
    /** Credentials for a [device] found by [Discovery], with its [token] and [key] as hex. */
    constructor(device: DiscoveredDevice, token: String = "", key: String = "") : this(
        device.name, device.id, device.ip, device.port, device.version, token, key, device.serialNumber,
    )
}
