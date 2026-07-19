package com.trafficwatch.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trafficwatch.app.core.domain.usecase.RegisterUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RegisterUiState(
    val name: String = "",
    val phoneNumber: String = "",
    val cnic: String = "",
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSuccess: Boolean = false
)

@HiltViewModel
class RegisterViewModel @Inject constructor(
    private val registerUseCase: RegisterUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(RegisterUiState())
    val uiState = _uiState.asStateFlow()

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value, error = null) }

    fun onPhoneNumberChange(value: String) {
        val digitsOnly = value.filter { it.isDigit() }.take(11)
        _uiState.update { it.copy(phoneNumber = digitsOnly, error = null) }
    }

    fun onCnicChange(value: String) {
        val digitsOnly = value.filter { it.isDigit() }.take(13)
        _uiState.update { it.copy(cnic = digitsOnly, error = null) }
    }

    fun onEmailChange(value: String) = _uiState.update { it.copy(email = value, error = null) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, error = null) }
    fun onConfirmPasswordChange(value: String) = _uiState.update { it.copy(confirmPassword = value, error = null) }

    fun register() {
        val state = _uiState.value
        when {
            state.name.isBlank() -> { _uiState.update { it.copy(error = "Name is required") }; return }
            state.phoneNumber.isBlank() -> { _uiState.update { it.copy(error = "Phone number is required") }; return }
            !PHONE_REGEX.matches(state.phoneNumber) -> { _uiState.update { it.copy(error = "Enter a valid phone number (e.g. 03001234567)") }; return }
            state.cnic.isBlank() -> { _uiState.update { it.copy(error = "CNIC is required") }; return }
            !CNIC_REGEX.matches(state.cnic) -> { _uiState.update { it.copy(error = "Enter a valid 13-digit CNIC") }; return }
            state.email.isBlank() -> { _uiState.update { it.copy(error = "Email is required") }; return }
            !EMAIL_REGEX.matches(state.email.trim()) -> { _uiState.update { it.copy(error = "Enter a valid email address") }; return }
            state.password.length < 8 -> { _uiState.update { it.copy(error = "Password must be at least 8 characters") }; return }
            state.password != state.confirmPassword -> { _uiState.update { it.copy(error = "Passwords do not match") }; return }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            registerUseCase(state.name.trim(), state.phoneNumber, state.cnic, state.email.trim(), state.password)
                .onSuccess { _uiState.update { it.copy(isLoading = false, isSuccess = true) } }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = e.message ?: "Registration failed") } }
        }
    }

    private companion object {
        val PHONE_REGEX = Regex("^03\\d{9}$")
        val CNIC_REGEX = Regex("^\\d{13}$")
        val EMAIL_REGEX = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    }
}
