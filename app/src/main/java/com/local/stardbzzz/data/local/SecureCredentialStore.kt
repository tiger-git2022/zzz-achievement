package com.local.stardbzzz.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class SavedCredentials(
    val username: String,
    val password: String,
    val sessionCookie: String,
)

class SecureCredentialStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val preferences = EncryptedSharedPreferences.create(
        context,
        "secure_credentials",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun credentials(): SavedCredentials? {
        val username = preferences.getString(Keys.Username, null)
        val password = preferences.getString(Keys.Password, null)
        val sessionCookie = preferences.getString(Keys.SessionCookie, null)
        return if (sessionCookie.isNullOrBlank()) {
            null
        } else {
            SavedCredentials(username.orEmpty(), password.orEmpty(), sessionCookie)
        }
    }

    fun saveCredentials(username: String, password: String, sessionCookie: String) {
        preferences.edit()
            .putString(Keys.Username, username)
            .putString(Keys.Password, password)
            .putString(Keys.SessionCookie, sessionCookie)
            .apply()
    }

    fun sessionCookie(): String? = preferences.getString(Keys.SessionCookie, null)

    fun authToken(): String? = preferences.getString(Keys.AuthToken, null)

    fun saveAuthToken(token: String?) {
        preferences.edit().putString(Keys.AuthToken, token.orEmpty()).apply()
    }

    fun clearSession() {
        preferences.edit()
            .remove(Keys.AuthToken)
            .remove(Keys.Username)
            .remove(Keys.Password)
            .remove(Keys.SessionCookie)
            .apply()
    }

    private object Keys {
        const val Username = "username"
        const val Password = "password"
        const val AuthToken = "auth_token"
        const val SessionCookie = "session_cookie"
    }
}
