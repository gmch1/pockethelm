package com.gmch.codexremote

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** RFC 6455 client over an SSH exec channel. The SSH channel already authenticates/encrypts the transport. */
class WebSocketTunnel(private val source: InputStream, private val sink: OutputStream) {
    private val random = SecureRandom()
    private val limit = 16 * 1024 * 1024
    fun handshake() {
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
        sink.write(("GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(Charsets.US_ASCII))
        sink.flush()
        val headers = ByteArrayOutputStream()
        var end = 0
        while (end != 0x0d0a0d0a) {
            val b = source.read(); if (b < 0) throw EOFException("App Server 握手前关闭连接")
            headers.write(b); end = (end shl 8) or b
            check(headers.size() <= 16384) { "无效的 WebSocket 握手" }
        }
        val lines = headers.toString("US-ASCII").split("\r\n")
        check(lines.first().startsWith("HTTP/1.1 101 ")) { "App Server WebSocket 握手失败：${lines.first()}" }
        val expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)))
        val fields = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        check(fields["sec-websocket-accept"] == expected && fields["upgrade"]?.lowercase() == "websocket" &&
            fields["connection"]?.lowercase()?.split(',')?.any { it.trim() == "upgrade" } == true) { "WebSocket 握手校验失败" }
    }

    @Synchronized private fun frame(op: Int, payload: ByteArray) {
        require(payload.size <= limit)
        sink.write(0x80 or op)
        when {
            payload.size < 126 -> sink.write(0x80 or payload.size)
            payload.size <= 65535 -> { sink.write(254); sink.write(payload.size shr 8); sink.write(payload.size and 255) }
            else -> { sink.write(255); for (shift in 7 downTo 0) sink.write(((payload.size.toLong() ushr (shift * 8)) and 255).toInt()) }
        }
        val mask = ByteArray(4).also(random::nextBytes)
        sink.write(mask)
        sink.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
        sink.flush()
    }

    val output = object : OutputStream() {
        private val buffer = ByteArrayOutputStream()
        override fun write(b: Int) {
            if (b == 10) { frame(1, buffer.toByteArray()); buffer.reset() }
            else { check(buffer.size() < limit) { "消息过大" }; buffer.write(b) }
        }
        override fun write(b: ByteArray, off: Int, len: Int) { for (i in off until off + len) write(b[i].toInt() and 255) }
    }
    val input = object : InputStream() {
        private var current = ByteArray(0)
        private var offset = 0
        private fun byte(): Int = source.read().also { if (it < 0) throw EOFException("App Server 已断开") }
        private fun message(): ByteArray {
            val result = ByteArrayOutputStream()
            var fragmented = false
            while (true) {
                val first = byte(); val second = byte(); val op = first and 15; val fin = first and 128 != 0
                check(first and 0x70 == 0 && second and 128 == 0) { "无效的服务器 WebSocket 帧" }
                var length = (second and 127).toLong()
                if (length >= 126) {
                    val count = if (length == 126L) 2 else 8
                    length = 0
                    repeat(count) { length = (length shl 8) or byte().toLong() }
                }
                check(length in 0..limit.toLong()) { "WebSocket 消息过大" }
                if (op >= 8) check(fin && length <= 125) { "无效的控制帧" }
                val payload = ByteArray(length.toInt()); var read = 0
                while (read < payload.size) {
                    val n = source.read(payload, read, payload.size - read)
                    if (n < 0) throw EOFException("消息传输中断")
                    read += n
                }
                when (op) {
                    8 -> { frame(8, payload); throw EOFException("App Server 关闭会话连接") }
                    9 -> frame(10, payload)
                    10 -> Unit
                    1, 0 -> {
                        check((op == 1 && !fragmented) || (op == 0 && fragmented)) { "无效的分片顺序" }
                        check(result.size().toLong() + length <= limit) { "WebSocket 消息过大" }
                        result.write(payload)
                        if (fin) return result.toByteArray() + byteArrayOf(10)
                        fragmented = true
                    }
                    else -> error("不支持的 WebSocket 帧：$op")
                }
            }
        }
        override fun read(): Int {
            if (offset == current.size) { current = message(); offset = 0 }
            return current[offset++].toInt() and 255
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (offset == current.size) { current = message(); offset = 0 }
            val n = minOf(len, current.size - offset)
            current.copyInto(b, off, offset, offset + n); offset += n
            return n
        }
    }
}
