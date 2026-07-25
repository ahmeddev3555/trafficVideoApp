package com.trafficwatch.server.auth

import com.trafficwatch.server.auth.dto.AuthResponse
import com.trafficwatch.server.auth.dto.LoginRequest
import com.trafficwatch.server.auth.dto.RegisterRequest
import com.trafficwatch.server.auth.exception.DuplicateEmailException
import com.trafficwatch.server.auth.exception.DuplicatePhoneNumberException
import com.trafficwatch.server.auth.exception.InvalidCredentialsException
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * `POST /auth/register` and `POST /auth/login` - both delegate straight to [AuthService]
 * and return the same [AuthResponse] wire shape (`{token, user: {id, name, email}}`).
 *
 * The `@ExceptionHandler`s below are a deliberately minimal, controller-local mapping of
 * [AuthService]'s exceptions to HTTP status so THIS task's tests can observe 409/401
 * behavior now. Task 6 owns building a general `@RestControllerAdvice`
 * `GlobalExceptionHandler` with a proper `ApiError{error, message}` body across all
 * controllers - once that exists, these handlers should be deleted in favor of it.
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

    @ExceptionHandler(DuplicatePhoneNumberException::class, DuplicateEmailException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleDuplicate() {
        // Body intentionally empty for now - Task 6's GlobalExceptionHandler will own
        // the response body shape (ApiError{error, message}).
    }

    @ExceptionHandler(InvalidCredentialsException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun handleInvalidCredentials() {
        // Body intentionally empty for now - see handleDuplicate() note above.
    }
}
