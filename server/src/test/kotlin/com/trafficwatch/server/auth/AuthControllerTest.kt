package com.trafficwatch.server.auth

import com.trafficwatch.server.config.SecurityConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * @WebMvcTest slice covering the actual HTTP wire contract: exact snake_case field
 * names, status codes for success/duplicate/bad-login, and the security filter chain
 * shape (permitAll on auth endpoints, authenticated() + JwtAuthFilter everywhere else).
 *
 * SecurityConfig and JwtAuthFilter are explicitly @Import'd since @WebMvcTest does not
 * scan arbitrary @Configuration classes. AuthService is wired for real (not mocked)
 * against a mockk'd UserRepository, so this also exercises the real register()/login()
 * logic end-to-end minus the database and minus the framework's servlet container.
 */
@WebMvcTest(AuthController::class)
@Import(SecurityConfig::class, JwtAuthFilter::class, AuthControllerTest.TestConfig::class)
class AuthControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jwtService: JwtService

    @TestConfiguration
    class TestConfig {
        @Bean
        fun userRepository(): UserRepository = mockk()

        @Bean
        fun jwtService(): JwtService = JwtService(
            secret = "web-mvc-test-only-jwt-signing-secret-do-not-use-elsewhere-0123456789",
            expirationDays = 30,
        )

        // PasswordEncoder is NOT redefined here - SecurityConfig (imported below) already
        // provides that bean, and redefining it here would collide with it.
        @Bean
        fun authService(
            userRepository: UserRepository,
            passwordEncoder: PasswordEncoder,
            jwtService: JwtService,
        ): AuthService = AuthService(userRepository, passwordEncoder, jwtService)
    }

    private val registerBody = """
        {
          "name": "Jane Doe",
          "phone_number": "03001234567",
          "cnic": "1234567890123",
          "email": "jane@example.com",
          "password": "supersecret1"
        }
    """.trimIndent()

    @Test
    fun `register with new phone and email returns 201 with snake_case AuthResponse wire shape`() {
        val fixedId = UUID.randomUUID()
        val savedUserSlot = slot<User>()
        every { userRepository.existsByPhoneNumber("03001234567") } returns false
        every { userRepository.existsByEmail("jane@example.com") } returns false
        every { userRepository.save(capture(savedUserSlot)) } answers {
            savedUserSlot.captured.apply { id = fixedId }
        }

        mockMvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.token").isNotEmpty)
            .andExpect(jsonPath("$.user.id").value(fixedId.toString()))
            .andExpect(jsonPath("$.user.name").value("Jane Doe"))
            .andExpect(jsonPath("$.user.email").value("jane@example.com"))
            // phone/cnic/password must never appear in the response wire shape.
            .andExpect(jsonPath("$.user.phone_number").doesNotExist())
            .andExpect(jsonPath("$.user.password").doesNotExist())
    }

    @Test
    fun `register with already-registered phone number returns 409 with ApiError body`() {
        every { userRepository.existsByPhoneNumber("03001234567") } returns true

        mockMvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("DUPLICATE_PHONE_NUMBER"))
            .andExpect(jsonPath("$.message").value("Phone number already registered: 03001234567"))
    }

    @Test
    fun `register with already-registered email returns 409`() {
        every { userRepository.existsByPhoneNumber("03001234567") } returns false
        every { userRepository.existsByEmail("jane@example.com") } returns true

        mockMvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody),
        )
            .andExpect(status().isConflict)
    }

    @Test
    fun `register when save races past the existence checks returns 409 via DataIntegrityViolationException fallback`() {
        // Simulates the documented TOCTOU race: existsByPhoneNumber/existsByEmail both
        // pass (say a concurrent request won in between), so AuthService proceeds to
        // save(), and the DB's unique constraint throws DataIntegrityViolationException
        // directly - not one of AuthService's friendlier typed exceptions. This must be
        // caught by GlobalExceptionHandler's generic fallback, not bubble up as a 500.
        every { userRepository.existsByPhoneNumber("03001234567") } returns false
        every { userRepository.existsByEmail("jane@example.com") } returns false
        every { userRepository.save(any()) } throws
            org.springframework.dao.DataIntegrityViolationException("duplicate key value violates unique constraint")

        mockMvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("DUPLICATE_RESOURCE"))
            .andExpect(jsonPath("$.message").isNotEmpty)
    }

    @Test
    fun `register with malformed phone number returns 400 with ApiError body`() {
        val invalidBody = """
            {
              "name": "Jane Doe",
              "phone_number": "not-a-phone",
              "cnic": "1234567890123",
              "email": "jane@example.com",
              "password": "supersecret1"
            }
        """.trimIndent()

        mockMvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidBody),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("Phone number must match 03XXXXXXXXX"))
    }

    @Test
    fun `login with correct credentials returns 200 with snake_case AuthResponse wire shape`() {
        val encoder = BCryptPasswordEncoder()
        val fixedId = UUID.randomUUID()
        val storedUser = User(
            name = "Jane Doe",
            phoneNumber = "03001234567",
            cnic = "1234567890123",
            email = "jane@example.com",
            passwordHash = encoder.encode("correct-password"),
        ).apply { id = fixedId }
        every { userRepository.findByEmail("jane@example.com") } returns storedUser

        val loginBody = """{"email": "jane@example.com", "password": "correct-password"}"""

        mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.token").isNotEmpty)
            .andExpect(jsonPath("$.user.id").value(fixedId.toString()))
            .andExpect(jsonPath("$.user.name").value("Jane Doe"))
            .andExpect(jsonPath("$.user.email").value("jane@example.com"))
    }

    @Test
    fun `login with unknown email returns 401 with ApiError body`() {
        every { userRepository.findByEmail("nobody@example.com") } returns null

        val loginBody = """{"email": "nobody@example.com", "password": "whatever12"}"""

        mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"))
            .andExpect(jsonPath("$.message").value("Invalid email or password"))
    }

    @Test
    fun `login with wrong password returns 401 - same as unknown email, no leak`() {
        val encoder = BCryptPasswordEncoder()
        val storedUser = User(
            name = "Jane Doe",
            phoneNumber = "03001234567",
            cnic = "1234567890123",
            email = "jane@example.com",
            passwordHash = encoder.encode("correct-password"),
        ).apply { id = UUID.randomUUID() }
        every { userRepository.findByEmail("jane@example.com") } returns storedUser

        val loginBody = """{"email": "jane@example.com", "password": "wrong-password"}"""

        mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody),
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `request without Authorization header to an unmapped path is rejected with 401 by the security chain`() {
        mockMvc.perform(get("/some/protected/resource"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `request with a malformed bearer token is rejected with 401, filter does not crash`() {
        mockMvc.perform(get("/some/protected/resource").header("Authorization", "Bearer not-a-real-jwt"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `request with a valid bearer token authenticates - reaches dispatch as 404, not 401`() {
        val token = jwtService.generateToken(UUID.randomUUID())

        mockMvc.perform(get("/some/protected/resource").header("Authorization", "Bearer $token"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `register and login endpoints are reachable without any Authorization header`() {
        every { userRepository.existsByPhoneNumber("03001234567") } returns true

        // Not asserting 201 here (phone already flagged duplicate) - only that the
        // request was NOT rejected at the security layer with 401, proving permitAll.
        mockMvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody),
        )
            .andExpect(status().isConflict)
    }
}
