package com.trafficwatch.app.core.domain.usecase

import com.trafficwatch.app.core.data.repository.AuthRepository
import com.trafficwatch.app.core.domain.model.User
import javax.inject.Inject

class RegisterUseCase @Inject constructor(private val authRepository: AuthRepository) {
    suspend operator fun invoke(name: String, email: String, password: String): Result<User> =
        authRepository.register(name, email, password)
}
