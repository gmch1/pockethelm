package com.gmch.codexremote.transport

import org.json.JSONObject

/** Native adapter transport seam. onResult runs in receive order before the next event. */
interface RpcEndpoint {
    suspend fun call(method: String, params: JSONObject = JSONObject(), timeout: Long = 45_000,
        onResult: (suspend (JSONObject) -> Unit)? = null): JSONObject
}
