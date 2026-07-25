package com.trafficwatch.server.auth

import com.trafficwatch.server.auth.dto.RegisterRequest
import com.trafficwatch.server.auth.exception.DuplicateEmailException
import com.trafficwatch.server.auth.exception.DuplicatePhoneNumberException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class AuthServiceTest {

    private val userRepository = mockk<UserRepository>()
    private val jwtService = mockk<JwtService>()
    private val authService = AuthService(userRepository, jwtService)

    private fun validRequest(
        phoneNumber: String = "03001234567",
        email: String = "person@example.com",
    ) = RegisterRequest(
        name = "Jane Doe",
        phoneNumber = phoneNumber,
        cnic = "1234567890123",
        email = email,
        password = "supersecret",
    )

    @Test
    fun `duplicate phone number throws DuplicatePhoneNumberException`() {
        val request = validRequest()
        every { userRepository.existsByPhoneNumber(request.phoneNumber) } returns true

        assertThatThrownBy { authService.register(request) }
            .isInstanceOf(DuplicatePhoneNumberException::class.java)

        verify(exactly = 0) { userRepository.existsByEmail(any()) }
        verify(exactly = 0) { userRepository.save(any()) }
        verify(exactly = 0) { jwtService.generateToken(any()) }
    }

    @Test
    fun `duplicate email throws DuplicateEmailException`() {
        val request = validRequest()
        every { userRepository.existsByPhoneNumber(request.phoneNumber) } returns false
        every { userRepository.existsByEmail(request.email) } returns true

        assertThatThrownBy { authService.register(request) }
            .isInstanceOf(DuplicateEmailException::class.java)

        verify(exactly = 0) { userRepository.save(any()) }
        verify(exactly = 0) { jwtService.generateToken(any()) }
    }

    @Test
    fun `successful registration returns AuthResponse with hashed password and no raw password leaked`() {
        val request = validRequest()
        val savedUserSlot = slot<User>()
        val fixedId = UUID.randomUUID()

        every { userRepository.existsByPhoneNumber(request.phoneNumber) } returns false
        every { userRepository.existsByEmail(request.email) } returns false
        every { userRepository.save(capture(savedUserSlot)) } answers {
            savedUserSlot.captured.apply { id = fixedId }
        }
        every { jwtService.generateToken(fixedId) } returns "signed.jwt.token"

        val response = authService.register(request)

        // The token must be the one JwtService actually issued for this user's id -
        // not a hardcoded/stub value - confirming AuthService no longer fabricates it.
        assertThat(response.token).isEqualTo("signed.jwt.token")
        verify(exactly = 1) { jwtService.generateToken(fixedId) }
        assertThat(response.user.id).isEqualTo(fixedId)
        assertThat(response.user.name).isEqualTo(request.name)
        assertThat(response.user.email).isEqualTo(request.email)

        // The persisted entity must carry a bcrypt hash, never the raw password.
        val savedUser = savedUserSlot.captured
        assertThat(savedUser.passwordHash).isNotEqualTo(request.password)
        assertThat(savedUser.passwordHash).startsWith("\$2") // bcrypt hash prefix ($2a$/$2b$/$2y$)

        // Inspect the actual object graph's string forms too, not just field presence,
        // to catch any accidental leak via toString()/logging.
        assertThat(response.toString()).doesNotContain(request.password)
        assertThat(response.user.toString()).doesNotContain(request.password)
    }

    @Test
    fun `RegisterRequest toString redacts password to prevent logging leaks`() {
        val request = validRequest()
        val requestString = request.toString()

        // Verify the raw password is never in the string representation
        assertThat(requestString).doesNotContain(request.password)
        assertThat(requestString).doesNotContain("supersecret")

        // Verify redaction marker is present
        assertThat(requestString).contains("[REDACTED]")

        // Verify other fields are still present and readable
        assertThat(requestString).contains(request.name)
        assertThat(requestString).contains(request.phoneNumber)
        assertThat(requestString).contains(request.cnic)
        assertThat(requestString).contains(request.email)
    }
}
