package com.trafficwatch.server.auth

import com.trafficwatch.server.auth.dto.AuthResponse
import com.trafficwatch.server.auth.dto.LoginRequest
import com.trafficwatch.server.auth.dto.RegisterRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * `POST /auth/register` and `POST /auth/login` - both delegate straight to [AuthService]
 * and return the same [AuthResponse] wire shape (`{token, user: {id, name, email}}`).
 *
 * Exceptions thrown by [AuthService] (duplicate phone/email, invalid credentials) and
 * validation failures from `@Valid` are mapped to HTTP status + an `ApiError` body by
 * `com.trafficwatch.server.common.GlobalExceptionHandler`, not locally in this class.
 */
@RestController
class AuthController(
    private val authService: AuthService,
) {

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody request: RegisterRequest): AuthResponse =
        authService.register(request)

    @PostMapping("/auth/login")
    fun login(@Valid @RequestBody request: LoginRequest): AuthResponse =
        authService.login(request)
}
