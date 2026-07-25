package com.trafficwatch.server.auth

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest @Autowired constructor(
    private val userRepository: UserRepository,
) {

    private fun newUser(phoneNumber: String, email: String) = User(
        name = "Test User",
        phoneNumber = phoneNumber,
        cnic = "12345-1234567-1",
        email = email,
        passwordHash = "hashed-password",
    )

    @Test
    fun `duplicate phone number insert throws DataIntegrityViolationException`() {
        userRepository.saveAndFlush(newUser(phoneNumber = "03001234567", email = "first@example.com"))

        assertThatThrownBy {
            userRepository.saveAndFlush(newUser(phoneNumber = "03001234567", email = "second@example.com"))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `duplicate email insert throws DataIntegrityViolationException`() {
        userRepository.saveAndFlush(newUser(phoneNumber = "03001111111", email = "dup@example.com"))

        assertThatThrownBy {
            userRepository.saveAndFlush(newUser(phoneNumber = "03002222222", email = "dup@example.com"))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `existsByPhoneNumber and existsByEmail reflect persisted state`() {
        userRepository.saveAndFlush(newUser(phoneNumber = "03003333333", email = "exists@example.com"))

        assertThat(userRepository.existsByPhoneNumber("03003333333")).isTrue()
        assertThat(userRepository.existsByEmail("exists@example.com")).isTrue()
        assertThat(userRepository.existsByPhoneNumber("00000000000")).isFalse()
        assertThat(userRepository.existsByEmail("nobody@example.com")).isFalse()
    }

    @Test
    fun `findByEmail returns persisted user, null when absent`() {
        val saved = userRepository.saveAndFlush(newUser(phoneNumber = "03004444444", email = "find@example.com"))

        val found = userRepository.findByEmail("find@example.com")

        assertThat(found).isNotNull
        assertThat(found?.id).isEqualTo(saved.id)
        assertThat(userRepository.findByEmail("absent@example.com")).isNull()
    }
}
