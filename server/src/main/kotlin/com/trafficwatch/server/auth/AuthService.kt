package com.trafficwatch.server.auth

import com.trafficwatch.server.auth.dto.AuthResponse
import com.trafficwatch.server.auth.dto.RegisterRequest
import com.trafficwatch.server.auth.dto.UserDto
import com.trafficwatch.server.auth.exception.DuplicateEmailException
import com.trafficwatch.server.auth.exception.DuplicatePhoneNumberException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val userRepository: UserRepository,
) {

    // Instantiated directly rather than injected as a `PasswordEncoder` bean: this task's
    // scope is limited to auth/ additions (no SecurityConfig yet). Task 5 introduces
    // SecurityConfig and is the more natural home for a shared PasswordEncoder bean -
    // at that point this should be replaced with a constructor-injected dependency.
    private val passwordEncoder = BCryptPasswordEncoder()

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

        return AuthResponse(
            // TEMPORARY placeholder token. Task 4 introduces the real JwtService and
            // replaces this with an actually-signed JWT.
            token = "stub-jwt-${saved.id}",
            user = UserDto(
                id = requireNotNull(saved.id) { "Saved user must have a generated id" },
                name = saved.name,
                email = saved.email,
            ),
        )
    }
}
