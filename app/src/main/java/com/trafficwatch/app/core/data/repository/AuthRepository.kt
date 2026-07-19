package com.trafficwatch.app.core.data.repository

import com.trafficwatch.app.core.data.remote.ApiService
import com.trafficwatch.app.core.data.remote.dto.LoginRequest
import com.trafficwatch.app.core.data.remote.dto.RegisterRequest
import com.trafficwatch.app.core.domain.model.User
import com.trafficwatch.app.core.util.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val apiService: ApiService,
    private val tokenStore: TokenStore
) {
    private val _isLoggedIn = MutableStateFlow(tokenStore.isLoggedIn())
    val isLoggedIn: Flow<Boolean> = _isLoggedIn.asStateFlow()

    suspend fun register(name: String, phoneNumber: String, cnic: String, email: String, password: String): Result<User> =
        runCatching {
            val response = apiService.register(RegisterRequest(name, phoneNumber, cnic, email, password))
            tokenStore.saveToken(response.token)
            tokenStore.saveUser(response.user.id, response.user.name, response.user.email)
            _isLoggedIn.value = true
            User(response.user.id, response.user.name, response.user.email)
        }

    suspend fun login(email: String, password: String): Result<User> =
        runCatching {
            val response = apiService.login(LoginRequest(email, password))
            tokenStore.saveToken(response.token)
            tokenStore.saveUser(response.user.id, response.user.name, response.user.email)
            _isLoggedIn.value = true
            User(response.user.id, response.user.name, response.user.email)
        }

    fun logout() {
        tokenStore.clear()
        _isLoggedIn.value = false
    }

    fun getCurrentUser(): User? {
        val id = tokenStore.getUserId() ?: return null
        val name = tokenStore.getUserName() ?: return null
        val email = tokenStore.getUserEmail() ?: return null
        return User(id, name, email)
    }
}
