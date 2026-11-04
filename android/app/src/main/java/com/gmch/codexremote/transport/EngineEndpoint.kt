package com.gmch.codexremote.transport

import com.gmch.codexremote.ConnectionProfile

enum class RpcFraming { JsonLines, WebSocket }
/** Trusted application registration, never executable text supplied by the remote API. */
interface EngineEndpoint {
    val id: String
    val framing: RpcFraming
    fun command(profile: ConnectionProfile): String
}
