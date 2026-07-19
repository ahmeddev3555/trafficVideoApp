package com.trafficwatch.app.core.data.remote

import com.trafficwatch.app.core.data.remote.dto.RegisterRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MockApiServiceTest {

    private fun request(
        name: String = "Ahmed Hussain",
        phoneNumber: String = "03001234567",
        cnic: String = "1234512345671",
        email: String = "ahmed@example.com",
        password: String = "password123"
    ) = RegisterRequest(name, phoneNumber, cnic, email, password)

    @Test
    fun `register succeeds for a new phone number`() = runTest {
        val service = MockApiService()

        val response = service.register(request())

        assertEquals("Ahmed Hussain", response.user.name)
        assertEquals("ahmed@example.com", response.user.email)
    }

    @Test
    fun `register throws DuplicatePhoneNumberException for a phone number already registered`() = runTest {
        val service = MockApiService()
        service.register(request())

        try {
            service.register(request(email = "someoneelse@example.com", cnic = "9876543210123"))
            error("Expected DuplicatePhoneNumberException to be thrown")
        } catch (e: DuplicatePhoneNumberException) {
            assertEquals("An account with this phone number already exists.", e.message)
        }
    }

    @Test
    fun `register succeeds for two different phone numbers`() = runTest {
        val service = MockApiService()
        service.register(request(phoneNumber = "03001234567"))

        val response = service.register(
            request(name = "Second User", phoneNumber = "03111234567", cnic = "9876543210123", email = "b@example.com")
        )

        assertEquals("Second User", response.user.name)
    }
}
