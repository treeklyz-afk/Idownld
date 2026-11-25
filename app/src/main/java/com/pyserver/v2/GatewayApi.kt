package com.pyserver.v2

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** The first authenticated next-job response doubles as preflight and is handed to the service,
 * so a gateway that leases work while polling does not lose that job during verification. */
data class GatewayPreflight(val payload: String)

object GatewayApi {
    fun fetchNextJob(gatewayUrl: String, token: String): GatewayPreflight {
        val base = gatewayUrl.trim().trimEnd('/')
        val parsed = URL(base)
        require(parsed.protocol.equals("https", ignoreCase = true) && parsed.host.isNotBlank() && parsed.userInfo == null) {
            "Gateway URL must be a valid HTTPS URL"
        }
        require(token.isNotBlank()) { "Enter the gateway API token" }
        val conn = (URL("$base/api/bridge/next-job").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 10000
            readTimeout = 10000
        }
        try {
            val code = conn.responseCode
            if (code == 401 || code == 403) throw IllegalStateException("Gateway rejected the token (HTTP $code). Check the token and access permissions.")
            if (code !in 200..299) throw IllegalStateException("Gateway verification failed (HTTP $code)")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body) // Reject non-JSON responses before enabling background polling.
            return GatewayPreflight(body)
        } finally {
            conn.disconnect()
        }
    }
}
