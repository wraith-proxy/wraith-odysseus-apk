package com.thomaspeoples.wraithodysseus.auth

import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SessionState
import okhttp3.HttpUrl

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
)

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
    suspend fun version(origin: HttpUrl): ServerVersion = TODO("U2")
    suspend fun status(origin: HttpUrl): AuthStatus = TODO("U2")
    suspend fun login(origin: HttpUrl, username: String, password: String, totpCode: String?): LoginResult = TODO("U2")
    suspend fun logout(): Unit = TODO("U2")
}
