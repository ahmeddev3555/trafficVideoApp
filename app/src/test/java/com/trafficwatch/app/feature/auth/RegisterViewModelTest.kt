package com.trafficwatch.app.feature.auth

import com.trafficwatch.app.core.domain.model.User
import com.trafficwatch.app.core.domain.usecase.RegisterUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RegisterViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val registerUseCase = mockk<RegisterUseCase>()
    private lateinit var viewModel: RegisterViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = RegisterViewModel(registerUseCase)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fillValidForm() {
        viewModel.onNameChange("Ahmed Hussain")
        viewModel.onPhoneNumberChange("03001234567")
        viewModel.onCnicChange("1234512345671")
        viewModel.onEmailChange("ahmed@example.com")
        viewModel.onPasswordChange("password123")
        viewModel.onConfirmPasswordChange("password123")
    }

    @Test
    fun `onPhoneNumberChange strips non-digits and caps at 11 characters`() {
        viewModel.onPhoneNumberChange("0300-123-4567-999")

        assertEquals("03001234567", viewModel.uiState.value.phoneNumber)
    }

    @Test
    fun `onCnicChange strips non-digits and caps at 13 characters`() {
        viewModel.onCnicChange("12345-1234567-19999")

        assertEquals("1234512345671", viewModel.uiState.value.cnic)
    }

    @Test
    fun `register fails when phone number is blank`() {
        fillValidForm()
        viewModel.onPhoneNumberChange("")

        viewModel.register()

        assertEquals("Phone number is required", viewModel.uiState.value.error)
    }

    @Test
    fun `register fails when phone number format is invalid`() {
        fillValidForm()
        viewModel.onPhoneNumberChange("12345")

        viewModel.register()

        assertEquals("Enter a valid phone number (e.g. 03001234567)", viewModel.uiState.value.error)
    }

    @Test
    fun `register fails when cnic is blank`() {
        fillValidForm()
        viewModel.onCnicChange("")

        viewModel.register()

        assertEquals("CNIC is required", viewModel.uiState.value.error)
    }

    @Test
    fun `register fails when cnic format is invalid`() {
        fillValidForm()
        viewModel.onCnicChange("12345")

        viewModel.register()

        assertEquals("Enter a valid 13-digit CNIC", viewModel.uiState.value.error)
    }

    @Test
    fun `register fails when email format is invalid`() {
        fillValidForm()
        viewModel.onEmailChange("not-an-email")

        viewModel.register()

        assertEquals("Enter a valid email address", viewModel.uiState.value.error)
    }

    @Test
    fun `register still enforces existing name, password length, and password match checks`() {
        fillValidForm()
        viewModel.onNameChange("")
        viewModel.register()
        assertEquals("Name is required", viewModel.uiState.value.error)

        fillValidForm()
        viewModel.onPasswordChange("short")
        viewModel.onConfirmPasswordChange("short")
        viewModel.register()
        assertEquals("Password must be at least 8 characters", viewModel.uiState.value.error)

        fillValidForm()
        viewModel.onConfirmPasswordChange("password124")
        viewModel.register()
        assertEquals("Passwords do not match", viewModel.uiState.value.error)
    }

    @Test
    fun `register succeeds with valid form and calls use case with trimmed fields in order`() {
        fillValidForm()
        val user = User(id = "u1", name = "Ahmed Hussain", email = "ahmed@example.com")
        coEvery {
            registerUseCase(
                "Ahmed Hussain",
                "03001234567",
                "1234512345671",
                "ahmed@example.com",
                "password123"
            )
        } returns Result.success(user)

        viewModel.register()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.isSuccess)
        assertEquals(false, viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `register surfaces duplicate phone number error from use case`() {
        fillValidForm()
        coEvery {
            registerUseCase(any(), any(), any(), any(), any())
        } returns Result.failure(Exception("An account with this phone number already exists."))

        viewModel.register()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("An account with this phone number already exists.", viewModel.uiState.value.error)
        assertEquals(false, viewModel.uiState.value.isSuccess)
    }
}
