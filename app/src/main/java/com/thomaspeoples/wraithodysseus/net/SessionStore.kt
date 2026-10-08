package com.thomaspeoples.wraithodysseus.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The odysseus_session cookie, reduced to the three things that decide
 * whether it may be sent. The password is never stored anywhere. */
data class SessionCookie(val value: String, val expiresAt: Long, val secure: Boolean)

/** What Http needs from storage. An interface only so JVM tests can hand Http
 * an in-memory fake instead of the Keystore-backed SessionStore. */
interface SessionState {
    val baseUrl: HttpUrl?
    var cookie: SessionCookie?
}

/**
 * The cookie value is sealed with an AES-256-GCM key that lives in the
 * Android Keystore and cannot be exported. Only ciphertext and IV reach
 * SharedPreferences ("auth"); the server origin and username are not secret
 * and sit in plain prefs ("conn"). If the key is ever gone (app data restored
 * elsewhere, Keystore wiped) decryption fails and the user just logs in again.
 */
class SessionStore(context: Context) : SessionState {
    private val auth = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val conn = context.getSharedPreferences("conn", Context.MODE_PRIVATE)

    override var baseUrl: HttpUrl?
        get() = conn.getString(KEY_BASE_URL, null)?.toHttpUrlOrNull()
        set(value) = conn.edit { putString(KEY_BASE_URL, value?.toString()) }

    var username: String?
        get() = conn.getString(KEY_USERNAME, null)
        set(value) = conn.edit { putString(KEY_USERNAME, value) }

    // Decrypted once per process; every request reads this.
    @Volatile private var cached: SessionCookie? = null
    @Volatile private var loaded = false

    override var cookie: SessionCookie?
        get() {
            if (!loaded) {
                cached = load()
                loaded = true
            }
            return cached
        }
        set(value) {
            cached = value
            loaded = true
            if (value == null) auth.edit { clear() } else save(value)
        }

    private fun save(c: SessionCookie) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(c.value.toByteArray(Charsets.UTF_8))
        auth.edit {
            putString(KEY_CIPHERTEXT, Base64.encodeToString(sealed, Base64.NO_WRAP))
            putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            putLong(KEY_EXPIRES_AT, c.expiresAt)
            putBoolean(KEY_SECURE, c.secure)
        }
    }

    private fun load(): SessionCookie? {
        val ct = auth.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = auth.getString(KEY_IV, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            val value = String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
            SessionCookie(value, auth.getLong(KEY_EXPIRES_AT, 0L), auth.getBoolean(KEY_SECURE, true))
        } catch (_: Exception) {
            auth.edit { clear() }
            null
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "odysseus_session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val KEY_IV = "iv"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_SECURE = "secure"
        const val KEY_BASE_URL = "base_url"
        const val KEY_USERNAME = "username"
    }
}
