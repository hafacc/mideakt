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
    /** The authentication token; empty for non-V3 devices. */
    val token: ByteArray,
    /** The authentication key; empty for non-V3 devices. */
    val key: ByteArray,
    /** The device's serial number, from discovery; null if the device reported none. */
    val serialNumber: String?,
) {
    /** Credentials for a [device] found by [Discovery], with its [token] and [key]. */
    constructor(device: DiscoveredDevice, token: ByteArray = ByteArray(0), key: ByteArray = ByteArray(0)) : this(
        device.name, device.id, device.ip, device.port, device.version, token, key, device.serialNumber,
    )

    override fun equals(other: Any?): Boolean =
        other is DeviceCredentials && name == other.name && id == other.id && ip == other.ip &&
            port == other.port && version == other.version && token.contentEquals(other.token) &&
            key.contentEquals(other.key) && serialNumber == other.serialNumber

    override fun hashCode(): Int =
        listOf(name, id, ip, port, version, token.contentHashCode(), key.contentHashCode(), serialNumber).hashCode()

    override fun toString(): String =
        "DeviceCredentials(name=$name, id=$id, ip=$ip, port=$port, version=$version, serialNumber=$serialNumber)"
}
