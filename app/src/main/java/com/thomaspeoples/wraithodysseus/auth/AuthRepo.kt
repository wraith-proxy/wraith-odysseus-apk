package com.thomaspeoples.wraithodysseus.auth

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** GET /api/version: no auth needed, so it doubles as the "is this really an
 * Odysseus server" probe when the URL is entered. */
data class ServerVersion(val version: String, val build: String)

/** GET /api/auth/status. Never 401s, so it is the cheap cookie-validity probe. */
data class AuthStatus(
    val configured: Boolean,
    val authenticated: Boolean,
    val username: String?,
    val isAdmin: Boolean,
    /** privileges.can_use_bash; false whenever privileges is absent. */
    val canUseBash: Boolean,
    /** The privileges set to false (can_use_research, can_manage_memory...). */
    val denied: Set<String> = emptySet(),
) {
    /** The web UI hides a control only when its privilege is false, never
     * when it is absent: admins and older servers send none. */
    fun allows(privilege: String) = privilege !in denied
}

sealed interface LoginResult {
    data class Ok(val username: String) : LoginResult
    /** 200 with requires_totp: re-POST the same username and password plus
     * the code, so the password must still be in memory for that call. */
    data object TotpRequired : LoginResult
    data class Failed(val message: String) : LoginResult
}

/** Username, password and optional TOTP in, odysseus_session cookie stored.
 * The password is never stored. */
class AuthRepo(private val http: Http, private val session: SessionState) {

    suspend fun version(origin: HttpUrl): ServerVersion =
        parseVersion(http.call(get(origin, "/api/version")))

    suspend fun status(origin: HttpUrl): AuthStatus =
        parseStatus(http.call(get(origin, "/api/auth/status")))

    /**
     * Any cookie already held is dropped first: it belongs to the session
     * this login replaces, possibly on another server. A Secure cookie that
     * arrives over plain http is refused rather than stored, because Http
     * would then never send it and every call after login would 401.
     */
    suspend fun login(origin: HttpUrl, username: String, password: String, totpCode: String?): LoginResult {
        session.cookie = null
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            // Without remember the server sets no Max-Age, and a session
            // cookie would not outlive this process.
            .put("remember", true)
        if (totpCode != null) body.put("totp_code", totpCode)
        val request = Request.Builder()
            .url(origin.resolve("/api/auth/login")!!)
            .post(body.toString().toRequestBody(JSON))
            .build()

        return try {
            http.open(request, Http.JSON_TIMEOUT_S).use { response ->
                val json = JSONObject(withContext(Dispatchers.IO) { response.body?.string().orEmpty() })
                if (json.optBoolean("requires_totp", false)) return LoginResult.TotpRequired
                val cookie = Http.sessionCookie(response) ?: return LoginResult.Failed(NO_COOKIE)
                if (cookie.secure && !origin.isHttps) return LoginResult.Failed(NEEDS_HTTPS)
                // Origin first: Http sends the cookie to whatever origin is
                // stored, so storing it the other way round would, for a
                // moment (or for good, if the process died in between), hand
                // this server's cookie to the previous one. Off Main: storing
                // the cookie encrypts it with a Keystore key, generated on
                // first use.
                withContext(Dispatchers.IO) {
                    session.baseUrl = origin
                    session.cookie = cookie
                }
                LoginResult.Ok(json.optString("username", username))
            }
        } catch (e: ApiException) {
            LoginResult.Failed(failureMessage(e))
        }
    }

    /** The server side is best effort: the local cookie goes whether or not
     * the server was reachable, so logging out always works offline. */
    suspend fun logout() {
        try {
            http.call(Request.Builder().url(http.url("/api/auth/logout")).post(ByteArray(0).toRequestBody()).build())
        } catch (_: Exception) {
            // Nothing to do: an unreachable server cannot be told.
        } finally {
            session.cookie = null
        }
    }

    /**
     * POST /api/auth/change-password (auth_routes.py:232) with a JSON body.
     * Throws ApiException with the server's own text ("Current password is
     * incorrect", "Password must be at least N characters"). The server ends
     * every other session of the user but keeps this one, so no new login is
     * needed. Never retried: a repeat after the first took effect would fail
     * on the old password.
     */
    suspend fun changePassword(current: String, new: String) {
        val body = JSONObject().put("current_password", current).put("new_password", new)
        http.call(
            Request.Builder().url(http.url("/api/auth/change-password")).post(body.toString().toRequestBody(JSON)).build(),
            retry = false,
        )
    }

    /** GET /api/auth/2fa/status: whether the account has two-factor sign-in on. Read only; setting it up stays in the web UI. */
    suspend fun totpEnabled(): Boolean =
        parseTotpStatus(http.call(Request.Builder().url(http.url("/api/auth/2fa/status")).build()))

    private fun get(origin: HttpUrl, path: String) = Request.Builder().url(origin.resolve(path)!!).build()

    companion object {
        private val JSON = "application/json".toMediaType()

        const val SETUP_REQUIRED = "Finish setup in the web UI"
        const val TOO_MANY = "Too many attempts, wait a minute"
        const val NEEDS_HTTPS = "This server requires HTTPS"
        const val NO_COOKIE = "The server did not start a session"

        internal fun parseVersion(body: String): ServerVersion {
            val json = JSONObject(body)
            return ServerVersion(json.getString("version"), json.optString("build", ""))
        }

        internal fun parseTotpStatus(body: String): Boolean = JSONObject(body).optBoolean("enabled", false)

        internal fun parseStatus(body: String): AuthStatus {
            val json = JSONObject(body)
            val privileges = json.optJSONObject("privileges")
            return AuthStatus(
                configured = json.optBoolean("configured", false),
                authenticated = json.optBoolean("authenticated", false),
                username = json.optString("username").takeIf { !json.isNull("username") && it.isNotEmpty() },
                isAdmin = json.optBoolean("is_admin", false),
                canUseBash = privileges?.optBoolean("can_use_bash", false) ?: false,
                denied = privileges?.keys()?.asSequence()
                    ?.filter { it.startsWith("can_") && privileges.opt(it) == false }?.toSet() ?: emptySet(),
            )
        }

        /** 401 text (`Invalid credentials`, `Invalid 2FA code`) is already
         * readable and is shown as is; 429 and "Setup required" get wording
         * that says what to do. There is no automatic retry. */
        internal fun failureMessage(e: ApiException): String = when {
            e.status == 429 -> TOO_MANY
            e.message == "Setup required" -> SETUP_REQUIRED
            else -> e.message
        }
    }
}

/** What the account screen knows about two-factor sign-in. */
sealed interface AccountState {
    data object Loading : AccountState
    data class Known(val enabled: Boolean) : AccountState
    data class Failed(val message: String) : AccountState
}

/**
 * The change-password form's own checks, before the server is asked: all
 * three fields filled and the new password typed twice the same. Length is
 * the server's rule (it says "Password must be at least N characters"), so
 * it is not guessed here.
 */
data class ChangePasswordForm(val current: String, val new: String, val again: String) {
    fun problem(): String? = when {
        current.isEmpty() -> "Enter your current password"
        new.isEmpty() -> "Enter a new password"
        new != again -> "The new passwords do not match"
        else -> null
    }
}
