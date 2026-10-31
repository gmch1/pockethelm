package com.gmch.codexremote

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

class WebSocketTunnelTest {
    private fun frame(op: Int, text: String, fin: Boolean = true): ByteArray {
        val bytes = text.toByteArray()
        return byteArrayOf(((if (fin) 128 else 0) or op).toByte(), bytes.size.toByte()) + bytes
    }
    @Test fun fragmentsAndInterleavedPingAreHandledWithoutDroppingText() {
        val output = ByteArrayOutputStream()
        val client = WebSocketTunnel((frame(1, "hel", false) + frame(9, "ping") + frame(0, "lo")).inputStream(), output)
        assertEquals("hello", client.input.bufferedReader().readLine())
        val pong = output.toByteArray()
        assertEquals(0x8a, pong[0].toInt() and 255)
        assertEquals(0x84, pong[1].toInt() and 255)
        assertEquals("ping", ByteArray(4) { (pong[6 + it].toInt() xor pong[2 + it].toInt()).toByte() }.toString(Charsets.UTF_8))
    }
    @Test fun outgoingFramesAreMaskedAndUseExtendedLengths() {
        val output = ByteArrayOutputStream()
        val client = WebSocketTunnel(byteArrayOf().inputStream(), output)
        val message = "测".repeat(150)
        client.output.write((message + "\n").toByteArray())
        val data = output.toByteArray()
        assertEquals(254, data[1].toInt() and 255)
        val length = ((data[2].toInt() and 255) shl 8) or (data[3].toInt() and 255)
        assertEquals(message.toByteArray().size, length)
        assertEquals(message, ByteArray(length) { (data[8 + it].toInt() xor data[4 + it % 4].toInt()).toByte() }.toString(Charsets.UTF_8))
    }
    @Test(expected = IllegalStateException::class) fun invalidContinuationIsRejected() {
        WebSocketTunnel(frame(0, "bad").inputStream(), ByteArrayOutputStream()).input.read()
    }
    @Test fun handshakeValidatesAcceptHeader() {
        var response: InputStream? = null
        val request = ByteArrayOutputStream()
        val output = object : OutputStream() {
            override fun write(b: Int) { request.write(b) }
            override fun flush() {
                val key = request.toString().lineSequence().first { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".byteInputStream()
            }
        }
        val input = object : InputStream() { override fun read() = response!!.read() }
        WebSocketTunnel(input, output).handshake()
    }
}
