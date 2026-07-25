package com.trafficwatch.server.auth

import com.trafficwatch.server.auth.dto.AuthResponse
import com.trafficwatch.server.auth.dto.LoginRequest
import com.trafficwatch.server.auth.dto.RegisterRequest
import com.trafficwatch.server.auth.dto.UserDto
import com.trafficwatch.server.auth.exception.DuplicateEmailException
import com.trafficwatch.server.auth.exception.DuplicatePhoneNumberException
import com.trafficwatch.server.auth.exception.InvalidCredentialsException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
) {

    /**
     * Registers a new user.
     *
     * Uniqueness is check-then-insert: existsByPhoneNumber/existsByEmail are queried first
     * purely as a fast, friendly error path (lets us throw a specific, targeted exception
     * before ever touching a save). This is NOT the sole correctness mechanism - two
     * concurrent registrations for the same phone/email can both pass these checks and
     * race to save(). The real safety net is the unique constraint added by Task 2's
     * migration, which will cause the losing save() to throw
     * org.springframework.dao.DataIntegrityViolationException. Translating that exception
     * into a friendly response is Task 6's job (a fallback case on top of this check).
     */
    fun register(request: RegisterRequest): AuthResponse {
        if (userRepository.existsByPhoneNumber(request.phoneNumber)) {
            throw DuplicatePhoneNumberException(request.phoneNumber)
        }
        if (userRepository.existsByEmail(request.email)) {
            throw DuplicateEmailException(request.email)
        }

        val user = User(
            name = request.name,
            phoneNumber = request.phoneNumber,
            cnic = request.cnic,
            email = request.email,
            passwordHash = passwordEncoder.encode(request.password),
        )

        val saved = userRepository.save(user)
        val userId = requireNotNull(saved.id) { "Saved user must have a generated id" }

        return AuthResponse(
            token = jwtService.generateToken(userId),
            user = UserDto(
                id = userId,
                name = saved.name,
                email = saved.email,
            ),
        )
    }

    /**
     * Authenticates an existing user by email + password.
     *
     * Deliberately throws the exact same [InvalidCredentialsException] whether the email
     * doesn't exist at all or the password doesn't match the stored hash - the client
     * (and anyone probing the endpoint) must not be able to distinguish "no such account"
     * from "wrong password" for a real account.
     */
    fun login(request: LoginRequest): AuthResponse {
        val user = userRepository.findByEmail(request.email) ?: throw InvalidCredentialsException()

        if (!passwordEncoder.matches(request.password, user.passwordHash)) {
            throw InvalidCredentialsException()
        }

        val userId = requireNotNull(user.id) { "Persisted user must have a generated id" }

        return AuthResponse(
            token = jwtService.generateToken(userId),
            user = UserDto(
                id = userId,
                name = user.name,
                email = user.email,
            ),
        )
    }
}
