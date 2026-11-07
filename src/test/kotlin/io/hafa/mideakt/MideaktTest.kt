package io.hafa.mideakt

import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MideaktTest {
    @Test
    fun targetTemperatureClampedOnEncode() {
        fun state(temperature: Double) = SetState(
            powerOn = true, targetTemperature = temperature, mode = OperationalMode.COOL,
            fanSpeed = FanSpeed.AUTO, eco = false, swingMode = SwingMode.OFF, turbo = false,
            sleep = false, fahrenheit = false, purifier = false, targetHumidity = 40,
            freezeProtection = false,
        )
        fun diff(left: ByteArray, right: ByteArray): Set<Int> =
            left.indices.filter { left[it] != right[it] }.toSet()

        // Two encodings of one setpoint differ only in the rolling message id and its checksums.
        val idOnlyDiff = diff(state(30.0).encode(), state(30.0).encode())
        // Over-range clamps to 30 °C; under-range to 17 °C — neither adds further byte differences.
        assertEquals(idOnlyDiff, diff(state(60.0).encode(), state(30.0).encode()))
        assertEquals(idOnlyDiff, diff(state(-5.0).encode(), state(17.0).encode()))
    }

    @Test
    fun aesEcbRoundTrip() {
        val key = Security.ENC_KEY
        val data = "the quick brown fox".toByteArray()
        assertTrue(Crypto.decryptECB(key, Crypto.encryptECB(key, data)).contentEquals(data))
    }

    @Test
    fun aesCbcRoundTrip() {
        val key = ByteArray(32) { 0xAB.toByte() }
        val data = ByteArray(32) { 0x11 }
        assertTrue(Crypto.decryptCBC(key, Crypto.encryptCBC(key, data)).contentEquals(data))
    }

    @Test
    fun v2PacketRoundTrip() {
        val command = Command.getState()
        val packet = V2Packet.encode(1108152157446L, command)
        assertTrue(V2Packet.decode(packet).contentEquals(command))
    }

    // Reference vectors computed from msmart's SmartHomeCloud formulas.
    @Test
    fun smartHomeSign() {
        assertEquals(
            "a7000535dd05fe33660ff3b19774af222021ce0e99a2e65ba3a1055e5981aa48",
            SmartHomeCloud.sign(
                """{"appId":"1010","loginAccount":"user@example.com"}""",
                "00112233445566778899aabbccddeeff",
            ),
        )
    }

    @Test
    fun smartHomePasswords() {
        assertEquals(
            "29bed488e8211e2d8524b272f0406fbae0e04a25ba1d75e91dd47b7f542aa781",
            SmartHomeCloud.encryptPassword("d1c6a1c0c7b2d4f6", "hunter2"),
        )
        assertEquals(
            "061ef8c952d6792901cfc5d11d49f512c71543392f824d8b229108b8b403fd37",
            SmartHomeCloud.iamPassword("d1c6a1c0c7b2d4f6", "hunter2"),
        )
    }

    /** A device that answers the handshake with an error packet has turned the token down. */
    @Test
    fun handshakeErrorPacketIsRejection() {
        ServerSocket(0).use { server ->
            val device = thread {
                server.accept().use { socket ->
                    socket.getInputStream().read(ByteArray(256))
                    socket.getOutputStream().write(
                        byteArrayOf(0x83.toByte(), 0x70, 0x00, 0x02, 0x20, 0x0F, 0x00, 0x00, 0x00, 0x00),
                    )
                    runCatching { socket.getInputStream().read() }
                }
            }
            val connection = MideaConnection("127.0.0.1", server.localPort, 1, 3)
            connection.connect()
            assertFailsWith<HandshakeRejectedException> { connection.authenticate(ByteArray(64), ByteArray(32)) }
            connection.disconnect()
            device.join(5_000)
        }
    }

    /** Values a unit reports without a name are kept, and the named ones print by name. */
    @Test
    fun namedValuesKeepUnnamedOnes() {
        assertEquals("COOL", OperationalMode(2).toString())
        assertEquals("mode(7)", OperationalMode(7).toString())
        assertEquals(FanSpeed.AUTO, FanSpeed(102))
        assertEquals("speed(55)", FanSpeed(55).toString())
        assertEquals(SwingMode.BOTH, SwingMode.HORIZONTAL + SwingMode.VERTICAL)
        assertTrue(SwingMode.VERTICAL in SwingMode.BOTH)
        assertFalse(SwingMode.BOTH in SwingMode.VERTICAL)
    }
}
