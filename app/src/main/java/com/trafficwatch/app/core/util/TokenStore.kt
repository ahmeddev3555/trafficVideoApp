package com.trafficwatch.app.core.util

import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_TOKEN = "auth_token"
private const val KEY_USER_ID = "user_id"
private const val KEY_USER_NAME = "user_name"
private const val KEY_USER_EMAIL = "user_email"
private const val KEY_DEVICE_ID = "device_id"

@Singleton
class TokenStore @Inject constructor(
    private val prefs: SharedPreferences
) {
    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun saveToken(token: String) = prefs.edit().putString(KEY_TOKEN, token).apply()

    fun saveUser(id: String, name: String, email: String) {
        prefs.edit()
            .putString(KEY_USER_ID, id)
            .putString(KEY_USER_NAME, name)
            .putString(KEY_USER_EMAIL, email)
            .apply()
    }

    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)
    fun getUserName(): String? = prefs.getString(KEY_USER_NAME, null)
    fun getUserEmail(): String? = prefs.getString(KEY_USER_EMAIL, null)

    fun isLoggedIn(): Boolean = getToken() != null

    fun clear() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_NAME)
            .remove(KEY_USER_EMAIL)
            .apply()
    }

    fun getOrCreateDeviceId(): String {
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        return deviceId
    }
}
