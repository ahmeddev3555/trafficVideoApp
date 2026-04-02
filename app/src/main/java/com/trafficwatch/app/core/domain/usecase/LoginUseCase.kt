package com.trafficwatch.app.core.domain.usecase

import com.trafficwatch.app.core.data.repository.AuthRepository
import com.trafficwatch.app.core.domain.model.User
import javax.inject.Inject

class LoginUseCase @Inject constructor(private val authRepository: AuthRepository) {
    suspend operator fun invoke(email: String, password: String): Result<User> =
        authRepository.login(email, password)
}
