package com.trafficwatch.server.auth.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * Wire request body for POST /v1/auth/register. Property names are camelCase;
 * the app-wide Jackson SNAKE_CASE naming strategy (see application.yml) maps
 * `phoneNumber` <-> `phone_number` etc. on the wire, matching Task 2's precedent.
 */
data class RegisterRequest(
    @field:NotBlank(message = "Name must not be blank")
    val name: String,

    @field:Pattern(regexp = "^03\\d{9}$", message = "Phone number must match 03XXXXXXXXX")
    val phoneNumber: String,

    @field:Pattern(regexp = "^\\d{13}$", message = "CNIC must be exactly 13 digits with no dashes")
    val cnic: String,

    @field:NotBlank(message = "Email must not be blank")
    @field:Email(message = "Email must be a well-formed email address")
    val email: String,

    @field:Size(min = 8, message = "Password must be at least 8 characters")
    val password: String,
)

/** Public-facing user representation - deliberately excludes phone, cnic, and password. */
data class UserDto(
    val id: UUID,
    val name: String,
    val email: String,
)

data class AuthResponse(
    val token: String,
    val user: UserDto,
)
