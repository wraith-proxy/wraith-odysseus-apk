package com.thomaspeoples.wraithodysseus.ui.settings

import com.thomaspeoples.wraithodysseus.chat.ModelChoice
import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * The per-user preference store, GET/PUT /api/prefs/{key}, which holds the
 * theme and the memory and skills switches, and the two ways the web UI
 * sets the default chat model.
 */
class PrefsApi(private val http: Http) {

    /** The stored value, or null when the key was never set. */
    suspend fun get(key: String): Any? {
        val text = http.call(Request.Builder().url(http.url("/api/prefs/${enc(key)}")).get().build())
        checkBody(text)
        return JSONObject(text).opt("value").takeUnless { it == JSONObject.NULL }
    }

    /** Stores [value] (a JSONObject, Boolean or String) under [key]. */
    suspend fun put(key: String, value: Any) {
        val body = JSONObject().put("value", value).toString().toRequestBody(JSON)
        checkBody(http.call(Request.Builder().url(http.url("/api/prefs/${enc(key)}")).put(body).build()))
    }

    /** A switch the web UI reads as `value !== false`: unset means on. */
    suspend fun flag(key: String): Boolean = get(key) != false

    suspend fun setFlag(key: String, on: Boolean) = put(key, on)

    /**
     * An admin's default is the server-wide one, set the way the web's
     * Settings does it (POST /api/auth/settings, admin only). Anyone else's
     * is their own preference, which is what GET /api/default-chat reads for
     * them; the web UI has no screen for that one.
     */
    suspend fun setDefaultModel(choice: ModelChoice, isAdmin: Boolean) {
        if (isAdmin) {
            val body = JSONObject()
                .put("default_endpoint_id", choice.endpointId)
                .put("default_model", choice.model)
                .toString().toRequestBody(JSON)
            http.call(Request.Builder().url(http.url("/api/auth/settings")).post(body).build(), retry = false)
        } else {
            put(KEY_DEFAULT_ENDPOINT, choice.endpointId)
            put(KEY_DEFAULT_MODEL, choice.model)
        }
    }

    companion object {
        const val KEY_MEMORY = "memory_enabled"
        const val KEY_AUTO_MEMORY = "auto_memory"
        const val KEY_SKILLS = "skills_enabled"
        const val KEY_DEFAULT_ENDPOINT = "default_endpoint_id"
        const val KEY_DEFAULT_MODEL = "default_model"

        private val JSON = "application/json".toMediaType()

        private fun enc(key: String) = java.net.URLEncoder.encode(key, "UTF-8")

        /** A 200 that is really a failure: `error`, or `success:false` with a `message`. */
        fun checkBody(body: String) {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: throw ApiException(200, "Unexpected response")
            (json.opt("error") as? String)?.takeIf { it.isNotBlank() }?.let { throw ApiException(200, it) }
            if (json.opt("success") == false) {
                throw ApiException(200, (json.opt("message") as? String)?.takeIf { it.isNotBlank() } ?: "Request failed")
            }
        }
    }
}
