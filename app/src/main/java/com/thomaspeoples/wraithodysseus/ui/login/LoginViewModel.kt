package com.thomaspeoples.wraithodysseus.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thomaspeoples.wraithodysseus.auth.AuthRepo
import com.thomaspeoples.wraithodysseus.auth.LoginResult
import com.thomaspeoples.wraithodysseus.demo.Demo
import com.thomaspeoples.wraithodysseus.net.HostPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl

data class LoginUiState(
    val serverUrl: String = "",
    /** "Odysseus 1.0.3" once the URL has answered /api/version. */
    val serverVersion: String? = null,
    val serverError: String? = null,
    val username: String = "",
    val password: String = "",
    val needsTotp: Boolean = false,
    val totpCode: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val loggedIn: Boolean = false,
)

/**
 * The password lives here, and only here, from typing until the login POST
 * that sets the cookie returns -- including across the TOTP step, which
 * re-sends it. It is blanked the moment login succeeds.
 *
 * [scope] is a constructor argument only so a JVM test can drive the
 * coroutines without a main looper.
 */
class LoginViewModel(
    private val repo: AuthRepo,
    initialUrl: String,
    initialUsername: String,
    /** Persists the server origin and username once a login has succeeded. */
    private val saveConnection: (origin: HttpUrl, username: String) -> Unit,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope) {

    private val _state = MutableStateFlow(LoginUiState(serverUrl = initialUrl, username = initialUsername))
    val state: StateFlow<LoginUiState> = _state

    private var serverCheck: Job? = null

    init {
        // Back here after the session expired: show the stored server's
        // version straight away rather than waiting for the field to blur.
        if (initialUrl.isNotBlank()) checkServer()
    }

    fun onServerUrl(value: String) {
        // A different server: whatever the old one said no longer applies,
        // including a check of it that has not answered yet.
        serverCheck?.cancel()
        _state.update {
            it.copy(serverUrl = value, serverVersion = null, serverError = null, needsTotp = false, totpCode = "", error = null)
        }
    }

    fun onUsername(value: String) = _state.update {
        it.copy(username = value, needsTotp = false, totpCode = "", error = null)
    }

    fun onPassword(value: String) = _state.update {
        it.copy(password = value, needsTotp = false, totpCode = "", error = null)
    }

    fun onTotpCode(value: String) = _state.update { it.copy(totpCode = value.filter(Char::isDigit).take(8), error = null) }

    /** HostPolicy first, then /api/version for the version line, then
     * /api/auth/status to catch a server nobody has finished setting up. */
    fun checkServer() {
        val origin = origin() ?: return
        serverCheck?.cancel()
        serverCheck = viewModelScope.launch {
            val error = try {
                val version = repo.version(origin)
                _state.update { it.copy(serverVersion = "Odysseus ${version.version}") }
                if (!repo.status(origin).configured) AuthRepo.SETUP_REQUIRED else null
            } catch (e: CancellationException) {
                // Replaced by a newer check; its text is not a server error.
                throw e
            } catch (e: Exception) {
                e.message ?: "Could not reach the server"
            }
            _state.update { it.copy(serverError = error) }
        }
    }

    fun submit() {
        val s = _state.value
        if (s.busy || s.username.isBlank() || s.password.isEmpty() || (s.needsTotp && s.totpCode.isEmpty())) return
        val origin = origin() ?: return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = try {
                repo.login(origin, s.username.trim(), s.password, s.totpCode.takeIf { s.needsTotp })
            } catch (e: Exception) {
                LoginResult.Failed(e.message ?: "Could not reach the server")
            }
            when (result) {
                is LoginResult.Ok -> {
                    saveConnection(origin, result.username)
                    _state.update { it.copy(busy = false, password = "", totpCode = "", loggedIn = true) }
                }
                LoginResult.TotpRequired -> _state.update { it.copy(busy = false, needsTotp = true) }
                is LoginResult.Failed -> _state.update {
                    it.copy(busy = false, error = result.message, totpCode = "")
                }
            }
        }
    }

    /** Into demo mode: no server, canned data, nothing saved. */
    fun startDemo() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            repo.startDemo()
            saveConnection(Demo.origin, Demo.USERNAME)
            _state.update { it.copy(busy = false, password = "", totpCode = "", loggedIn = true) }
        }
    }

    private fun origin(): HttpUrl? = when (val v = HostPolicy.check(_state.value.serverUrl)) {
        is HostPolicy.Verdict.Ok -> v.origin
        is HostPolicy.Verdict.Rejected -> {
            _state.update { it.copy(serverError = v.reason, serverVersion = null) }
            null
        }
    }
}
