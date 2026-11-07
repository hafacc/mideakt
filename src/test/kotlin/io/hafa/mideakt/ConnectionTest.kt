package io.hafa.mideakt

import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ConnectionTest {
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
}
