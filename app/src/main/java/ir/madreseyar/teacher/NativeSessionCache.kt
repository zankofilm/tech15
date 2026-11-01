package ir.madreseyar.teacher

import org.json.JSONObject

/**
 * In-memory session cache used only while the app process is alive.
 * Nothing is persisted here; disk persistence remains protected by Android Keystore.
 */
object NativeSessionCache {
    @Volatile private var payloadJson: String? = null

    fun put(session: String, token: String, sessionKey: String, tokenKey: String) {
        if (session.isBlank() || token.isBlank()) return
        payloadJson = JSONObject()
            .put("session", session)
            .put("token", token)
            .put("sessionKey", sessionKey.ifBlank { "madreseyar.session.v14" })
            .put("tokenKey", tokenKey.ifBlank { "madreseyar.staff.token.v14" })
            .toString()
    }

    fun putPayload(payload: String?) {
        if (payload.isNullOrBlank()) return
        payloadJson = payload
    }

    fun payload(): String? = payloadJson

    fun token(): String = try {
        JSONObject(payloadJson ?: "{}").optString("token")
    } catch (_: Exception) {
        ""
    }

    fun clear() {
        payloadJson = null
    }
}
