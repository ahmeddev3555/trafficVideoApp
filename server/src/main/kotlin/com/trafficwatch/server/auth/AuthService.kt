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
     * Fixed dummy hash compared against on the unknown-email login path, purely to burn
     * comparable bcrypt time to a real password check - the actual match result is
     * discarded. Computed once via the injected [passwordEncoder] at construction time,
     * not a freshly-instantiated encoder, so this doesn't reintroduce the duplicate
     * `BCryptPasswordEncoder()` instance that constructor injection was meant to remove.
     */
    private val dummyPasswordHash: String = passwordEncoder.encode("dummy-password-for-timing-safety")

    /**
     * Registers a new user.
     *
     * Uniqueness is check-then-insert: existsByPhoneNumber/existsByEmail are queried first
     * purely as a fast, friendly error path (lets us throw a specific, targeted exception
     * before ever touching a save). This is NOT the sole correctness mechanism - two
     * concurrent registrations for the same phone/email can both pass these checks and
     * race to save(). The real safety net is the unique constraint added by Task 2's
     * migration, which will cause the losing save() to throw
     * org.springframework.dao.DataIntegrityViolationException. That exception is
     * translated into a friendly 409 response by
     * `com.trafficwatch.server.common.GlobalExceptionHandler`'s fallback handler.
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
     *
     * This must also be true of *response timing*, not just the response body: a real
     * bcrypt comparison (~50-100ms) is deliberately slow, so if the unknown-email path
     * returned immediately while the wrong-password path always ran a comparison, an
     * attacker could still tell the two cases apart by measuring latency. To close that
     * side-channel, the unknown-email path performs a throwaway comparison against
     * [dummyPasswordHash] before failing, so both paths take comparable time.
     */
    fun login(request: LoginRequest): AuthResponse {
        val user = userRepository.findByEmail(request.email)
        if (user == null) {
            passwordEncoder.matches(request.password, dummyPasswordHash)
            throw InvalidCredentialsException()
        }

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
