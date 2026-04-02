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
    fun onEmailChange(value: String) = _uiState.update { it.copy(email = value, error = null) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, error = null) }
    fun onConfirmPasswordChange(value: String) = _uiState.update { it.copy(confirmPassword = value, error = null) }

    fun register() {
        val state = _uiState.value
        when {
            state.name.isBlank() -> { _uiState.update { it.copy(error = "Name is required") }; return }
            state.email.isBlank() -> { _uiState.update { it.copy(error = "Email is required") }; return }
            state.password.length < 8 -> { _uiState.update { it.copy(error = "Password must be at least 8 characters") }; return }
            state.password != state.confirmPassword -> { _uiState.update { it.copy(error = "Passwords do not match") }; return }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            registerUseCase(state.name.trim(), state.email.trim(), state.password)
                .onSuccess { _uiState.update { it.copy(isLoading = false, isSuccess = true) } }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = e.message ?: "Registration failed") } }
        }
    }
}
