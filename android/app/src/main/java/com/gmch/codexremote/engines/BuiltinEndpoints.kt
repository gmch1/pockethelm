package com.gmch.codexremote.engines

import com.gmch.codexremote.ConnectionProfile
import com.gmch.codexremote.shellQuote
import com.gmch.codexremote.transport.EngineEndpoint
import com.gmch.codexremote.transport.RpcFraming

object BuiltinEndpoints {
    val Codex = object : EngineEndpoint {
        override val id = "codex"
        override val framing = RpcFraming.WebSocket
        override fun command(profile: ConnectionProfile): String {
            if (profile.useDeviceKey && profile.socket.isBlank()) return "pockethelm-codex-v1"
            val bin = shellQuote(profile.binary)
            val proxy = "$bin app-server proxy" + if (profile.socket.isNotBlank()) " --sock ${shellQuote(profile.socket)}" else ""
            val command = if (profile.socket.isBlank()) "$bin app-server daemon start >/dev/null && exec $proxy" else "exec $proxy"
            return "bash -lc ${shellQuote(command)}"
        }
    }
    val Zcode = object : EngineEndpoint {
        override val id = "zcode"
        override val framing = RpcFraming.JsonLines
        override fun command(profile: ConnectionProfile) = "pockethelm-zcode-v1"
    }
}
