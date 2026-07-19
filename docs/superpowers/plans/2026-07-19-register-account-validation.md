# Register Account Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add email format validation, required Phone Number + CNIC fields (with format validation and CNIC auto-dashing), and server-enforced phone-number uniqueness to the Create Account flow.

**Architecture:** Thread `phoneNumber`/`cnic` through the existing layered flow (`RegisterScreen` → `RegisterViewModel` → `RegisterUseCase` → `AuthRepository` → `ApiService`/`MockApiService`), extending the existing client-side `when`-chain validation pattern and the existing `Result`/Snackbar error-handling pattern. `MockApiService` gains an in-memory phone-number registry to simulate server-side uniqueness enforcement.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, JUnit4, MockK, kotlinx-coroutines-test (first unit tests in this repo).

**Spec:** `docs/superpowers/specs/2026-07-19-register-account-validation-design.md`

---

## Environment notes (read before running any command)

- `gradlew.bat`'s wrapper jar is not committed to this repo, so `./gradlew` will fail with `Could not find or load main class org.gradle.wrapper.GradleWrapperMain`. Invoke Gradle directly via the cached distribution instead.
- `JAVA_HOME` is not set in the shell by default. Use Android Studio's bundled JBR.

Every `Run:` command in this plan uses this exact preamble (PowerShell):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$gradle = "C:\Users\AHussain\.gradle\wrapper\dists\gradle-8.6-bin\afr5mpiioh2wthjmwnkmdsd5w\gradle-8.6\bin\gradle.bat"
```

---

## Task 1: CNIC dash-formatting function

**Files:**
- Create: `app/src/main/java/com/trafficwatch/app/feature/auth/CnicFormatter.kt`
- Test: `app/src/test/java/com/trafficwatch/app/feature/auth/CnicFormatterTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.trafficwatch.app.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class CnicFormatterTest {

    @Test
    fun `formats complete 13-digit cnic with dashes after 5th and 12th digit`() {
        assertEquals("12345-1234567-1", formatCnicWithDashes("1234512345671"))
    }

    @Test
    fun `formats partial input without a trailing dash`() {
        assertEquals("1234", formatCnicWithDashes("1234"))
        assertEquals("12345", formatCnicWithDashes("12345"))
        assertEquals("12345-6", formatCnicWithDashes("123456"))
        assertEquals("12345-1234567", formatCnicWithDashes("123451234567"))
    }

    @Test
    fun `formats empty input as empty string`() {
        assertEquals("", formatCnicWithDashes(""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$gradle = "C:\Users\AHussain\.gradle\wrapper\dists\gradle-8.6-bin\afr5mpiioh2wthjmwnkmdsd5w\gradle-8.6\bin\gradle.bat"
& $gradle testDebugUnitTest --tests "com.trafficwatch.app.feature.auth.CnicFormatterTest" --console=plain
```

Expected: FAIL — `formatCnicWithDashes` is unresolved (function doesn't exist yet).

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.trafficwatch.app.feature.auth

/**
 * Formats raw CNIC digits as 12345-1234567-1 (5-7-1 grouping), inserting a dash
 * after the 5th and 12th digit. Works on partial input for live formatting as
 * the user types.
 */
fun formatCnicWithDashes(rawDigits: String): String {
    val builder = StringBuilder()
    for (i in rawDigits.indices) {
        if (i == 5 || i == 12) builder.append('-')
        builder.append(rawDigits[i])
    }
    return builder.toString()
}
```

- [ ] **Step 4: Run test to verify it passes**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$gradle = "C:\Users\AHussain\.gradle\wrapper\dists\gradle-8.6-bin\afr5mpiioh2wthjmwnkmdsd5w\gradle-8.6\bin\gradle.bat"
& $gradle testDebugUnitTest --tests "com.trafficwatch.app.feature.auth.CnicFormatterTest" --console=plain
```

Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/feature/auth/CnicFormatter.kt app/src/test/java/com/trafficwatch/app/feature/auth/CnicFormatterTest.kt
git commit -m "Add CNIC dash-formatting function"
```

---

## Task 2: RegisterRequest DTO fields + MockApiService phone-number uniqueness

**Files:**
- Modify: `app/src/main/java/com/trafficwatch/app/core/data/remote/dto/AuthDtos.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/core/data/remote/MockApiService.kt`
- Test: `app/src/test/java/com/trafficwatch/app/core/data/remote/MockApiServiceTest.kt`

- [ ] **Step 1: Update RegisterRequest DTO**

Replace the `RegisterRequest` data class in `app/src/main/java/com/trafficwatch/app/core/data/remote/dto/AuthDtos.kt`:

```kotlin
data class RegisterRequest(
    @SerializedName("name") val name: String,
    @SerializedName("phone_number") val phoneNumber: String,
    @SerializedName("cnic") val cnic: String,
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String
)
```

(Leave `LoginRequest`, `AuthResponse`, `UserDto` unchanged.)

- [ ] **Step 2: Write the failing test for MockApiService**

```kotlin
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
```

- [ ] **Step 3: Run test to verify it fails**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$gradle = "C:\Users\AHussain\.gradle\wrapper\dists\gradle-8.6-bin\afr5mpiioh2wthjmwnkmdsd5w\gradle-8.6\bin\gradle.bat"
& $gradle testDebugUnitTest --tests "com.trafficwatch.app.core.data.remote.MockApiServiceTest" --console=plain
```

Expected: FAIL to compile — `RegisterRequest(...)` call doesn't match the old 3-arg constructor yet, and `DuplicatePhoneNumberException` doesn't exist.

- [ ] **Step 4: Implement the uniqueness check in MockApiService**

In `app/src/main/java/com/trafficwatch/app/core/data/remote/MockApiService.kt`, add the import and exception class, and update `register()`:

```kotlin
import java.util.concurrent.ConcurrentHashMap
```

```kotlin
class DuplicatePhoneNumberException(message: String) : Exception(message)
```

```kotlin
@Singleton
class MockApiService @Inject constructor() : ApiService {

    private val registeredPhoneNumbers = ConcurrentHashMap.newKeySet<String>()

    // Simulate network latency
    private suspend fun fakeDelay() = delay(800)

    override suspend fun register(request: RegisterRequest): AuthResponse {
        fakeDelay()
        if (!registeredPhoneNumbers.add(request.phoneNumber)) {
            throw DuplicatePhoneNumberException("An account with this phone number already exists.")
        }
        return AuthResponse(
            token = "mock_token_${UUID.randomUUID()}",
            user = UserDto(
                id = UUID.randomUUID().toString(),
                name = request.name,
                email = request.email
            )
        )
    }

    // ... rest of the class (login, submitReport, getReportStatus, getReports) unchanged
```

(`Set.add()` returns `false` if the element was already present, so this is a single atomic check-and-register — no separate `contains` check needed.)

- [ ] **Step 5: Run test to verify it passes**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$gradle = "C:\Users\AHussain\.gradle\wrapper\dists\gradle-8.6-bin\afr5mpiioh2wthjmwnkmdsd5w\gradle-8.6\bin\gradle.bat"
& $gradle testDebugUnitTest --tests "com.trafficwatch.app.core.data.remote.MockApiServiceTest" --console=plain
```

Expected: PASS (3 tests). Note this will also recompile the whole module — any other code still calling the old 3-arg `RegisterRequest(...)` constructor will now fail to compile; that's expected and fixed in Task 3.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/core/data/remote/dto/AuthDtos.kt app/src/main/java/com/trafficwatch/app/core/data/remote/MockApiService.kt app/src/test/java/com/trafficwatch/app/core/data/remote/MockApiServiceTest.kt
git commit -m "Add phone_number/cnic to RegisterRequest and enforce phone-number uniqueness in MockApiService"
```

---

## Task 3: Thread phoneNumber/cnic through AuthRepository and RegisterUseCase

**Files:**
- Modify: `app/src/main/java/com/trafficwatch/app/core/data/repository/AuthRepository.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/core/domain/usecase/RegisterUseCase.kt`

No dedicated unit test for this task: both changes are pure parameter pass-through with no branching logic, already exercised indirectly by the `RegisterViewModelTest` written in Task 4.

- [ ] **Step 1: Update AuthRepository.register**

In `app/src/main/java/com/trafficwatch/app/core/data/repository/AuthRepository.kt`, replace the `register` function:

```kotlin
suspend fun register(name: String, phoneNumber: String, cnic: String, email: String, password: String): Result<User> =
    runCatching {
        val response = apiService.register(RegisterRequest(name, phoneNumber, cnic, email, password))
        tokenStore.saveToken(response.token)
        tokenStore.saveUser(response.user.id, response.user.name, response.user.email)
        _isLoggedIn.value = true
        User(response.user.id, response.user.name, response.user.email)
    }
```

- [ ] **Step 2: Update RegisterUseCase**

Replace the contents of `app/src/main/java/com/trafficwatch/app/core/domain/usecase/RegisterUseCase.kt`:

```kotlin
package com.trafficwatch.app.core.domain.usecase

import com.trafficwatch.app.core.data.repository.AuthRepository
import com.trafficwatch.app.core.domain.model.User
import javax.inject.Inject

class RegisterUseCase @Inject constructor(private val authRepository: AuthRepository) {
    suspend operator fun invoke(name: String, phoneNumber: String, cnic: String, email: String, password: String): Result<User> =
        authRepository.register(name, phoneNumber, cnic, email, password)
}
```

- [ ] **Step 3: Compile to confirm no other callers are broken**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$gradle = "C:\Users\AHussain\.gradle\wrapper\dists\gradle-8.6-bin\afr5mpiioh2wthjmwnkmdsd5w\gradle-8.6\bin\gradle.bat"
& $gradle compileDebugKotlin --console=plain
```

Expected: FAIL — `RegisterViewModel.kt`'s `register()` still calls `registerUseCase(state.name.trim(), state.email.trim(), state.password)` with the old 3-arg signature, which no longer matches the 5-arg `invoke` just defined. This is expected; fixed in Task 4.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/core/data/repository/AuthRepository.kt app/src/main/java/com/trafficwatch/app/core/domain/usecase/RegisterUseCase.kt
git commit -m "Thread phoneNumber/cnic through AuthRepository.register and RegisterUseCase"
```

---

## Task 4: RegisterViewModel state, validation, and unit tests

**Files:**
- Modify: `app/src/main/java/com/trafficwatch/app/feature/auth/RegisterViewModel.kt`
- Test: `app/src/test/java/com/trafficwatch/app/feature/auth/RegisterViewModelTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
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
```

- [ ] **Step 2: Run tests to verify they fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$gradle = "C:\Users\AHussain\.gradle\wrapper\dists\gradle-8.6-bin\afr5mpiioh2wthjmwnkmdsd5w\gradle-8.6\bin\gradle.bat"
& $gradle testDebugUnitTest --tests "com.trafficwatch.app.feature.auth.RegisterViewModelTest" --console=plain
```

Expected: FAIL to compile — `onPhoneNumberChange`/`onCnicChange` don't exist yet, and `registerUseCase(...)` 5-arg mock setup doesn't match the current 3-arg `invoke`.

- [ ] **Step 3: Implement RegisterUiState, onChange handlers, and validation**

Replace the contents of `app/src/main/java/com/trafficwatch/app/feature/auth/RegisterViewModel.kt`:

```kotlin
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
            !EMAIL_REGEX.matches(state.email) -> { _uiState.update { it.copy(error = "Enter a valid email address") }; return }
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
```

- [ ] **Step 4: Run tests to verify they pass**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$gradle = "C:\Users\AHussain\.gradle\wrapper\dists\gradle-8.6-bin\afr5mpiioh2wthjmwnkmdsd5w\gradle-8.6\bin\gradle.bat"
& $gradle testDebugUnitTest --tests "com.trafficwatch.app.feature.auth.RegisterViewModelTest" --console=plain
```

Expected: PASS (10 tests).

- [ ] **Step 5: Run the full unit test suite to confirm nothing else broke**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$gradle = "C:\Users\AHussain\.gradle\wrapper\dists\gradle-8.6-bin\afr5mpiioh2wthjmwnkmdsd5w\gradle-8.6\bin\gradle.bat"
& $gradle testDebugUnitTest --console=plain
```

Expected: PASS (16 tests total: 3 CnicFormatterTest + 3 MockApiServiceTest + 10 RegisterViewModelTest).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/feature/auth/RegisterViewModel.kt app/src/test/java/com/trafficwatch/app/feature/auth/RegisterViewModelTest.kt
git commit -m "Add phone/CNIC state, validation, and email format check to RegisterViewModel"
```

---

## Task 5: CNIC VisualTransformation and RegisterScreen UI

**Files:**
- Create: `app/src/main/java/com/trafficwatch/app/feature/auth/CnicVisualTransformation.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/feature/auth/RegisterScreen.kt`

No automated test for this task — Compose UI rendering and cursor/IME behavior are verified manually in Task 6, consistent with how the rest of this app's UI is tested.

- [ ] **Step 1: Create the CNIC VisualTransformation**

```kotlin
package com.trafficwatch.app.feature.auth

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Renders raw CNIC digits as 12345-1234567-1 for display; underlying state stays raw digits. */
class CnicVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val formatted = formatCnicWithDashes(text.text)
        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                var transformed = offset
                if (offset > 5) transformed++
                if (offset > 12) transformed++
                return transformed
            }

            override fun transformedToOriginal(offset: Int): Int {
                var original = offset
                if (offset > 6) original--
                if (offset > 14) original--
                return original.coerceIn(0, text.text.length)
            }
        }
        return TransformedText(AnnotatedString(formatted), offsetMapping)
    }
}
```

- [ ] **Step 2: Add Phone Number and CNIC fields to RegisterScreen**

In `app/src/main/java/com/trafficwatch/app/feature/auth/RegisterScreen.kt`, add these imports:

```kotlin
import androidx.compose.ui.text.input.KeyboardType
```

(`KeyboardType` is already imported — check before adding a duplicate.)

Insert two new `OutlinedTextField`s between the existing "Full Name" field and the existing "Email" field:

```kotlin
OutlinedTextField(
    value = uiState.phoneNumber,
    onValueChange = viewModel::onPhoneNumberChange,
    label = { Text("Phone Number (03XXXXXXXXX)") },
    singleLine = true,
    keyboardOptions = KeyboardOptions(
        keyboardType = KeyboardType.Phone,
        imeAction = ImeAction.Next
    ),
    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
    modifier = Modifier.fillMaxWidth()
)

OutlinedTextField(
    value = uiState.cnic,
    onValueChange = viewModel::onCnicChange,
    label = { Text("CNIC") },
    singleLine = true,
    visualTransformation = CnicVisualTransformation(),
    keyboardOptions = KeyboardOptions(
        keyboardType = KeyboardType.Number,
        imeAction = ImeAction.Next
    ),
    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
    modifier = Modifier.fillMaxWidth()
)
```

The full field order in the `Column` is now: Full Name → Phone Number → CNIC → Email → Password → Confirm Password. No other fields need their `keyboardActions`/`imeAction` changed — the `Next`/`Down` chain already flows through whatever order the fields appear in.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/feature/auth/CnicVisualTransformation.kt app/src/main/java/com/trafficwatch/app/feature/auth/RegisterScreen.kt
git commit -m "Add Phone Number and CNIC fields to RegisterScreen with CNIC auto-dashing"
```

---

## Task 6: Manual verification on device

**Files:** none (verification only)

- [ ] **Step 1: Build and install the debug APK**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$gradle = "C:\Users\AHussain\.gradle\wrapper\dists\gradle-8.6-bin\afr5mpiioh2wthjmwnkmdsd5w\gradle-8.6\bin\gradle.bat"
& $gradle installDebug --console=plain
```

Expected: `BUILD SUCCESSFUL`, `Installed on 1 device.` (requires a device connected via `adb devices`).

- [ ] **Step 2: Exercise the Create Account form on-device**

Launch the app, navigate to Login → "Don't have an account? Sign up", and check each of the following produces the expected Snackbar message (fill all other fields validly each time):

| Input | Expected message |
|---|---|
| Name blank | "Name is required" |
| Phone blank | "Phone number is required" |
| Phone = `12345` | "Enter a valid phone number (e.g. 03001234567)" |
| CNIC blank | "CNIC is required" |
| CNIC with fewer than 13 digits | "Enter a valid 13-digit CNIC" |
| Email = `not-an-email` | "Enter a valid email address" |
| Password = `short` | "Password must be at least 8 characters" |
| Confirm password mismatched | "Passwords do not match" |

- [ ] **Step 3: Verify CNIC auto-dashing while typing**

Type `1234512345671` into the CNIC field one digit at a time; confirm dashes appear live as `12345-1234567-1` and the field never accepts more than 13 digits.

- [ ] **Step 4: Verify successful registration**

Fill all fields validly (e.g. name "Test User", phone `03001234567`, CNIC `1234512345671`, email `test@example.com`, password/confirm `password123`) and tap "Create Account". Confirm it navigates to the Permissions screen (same as today's registration success flow).

- [ ] **Step 5: Verify duplicate phone number rejection**

Log out, go to Sign Up again, and register a second account using the same phone number `03001234567` (different name/email/CNIC). Confirm the Snackbar shows "An account with this phone number already exists." and the app stays on the Register screen.

No commit for this task — verification only, no code changes.

---

## Self-review notes

- **Spec coverage:** email format validation (Task 4), Phone Number field + format validation (Tasks 4–5), CNIC field + format validation + auto-dashing (Tasks 1, 4, 5), phone-number uniqueness at submission (Task 2) — all covered. Manual UI verification (Task 6) covers the spec's testing section.
- **Type/signature consistency:** parameter order `(name, phoneNumber, cnic, email, password)` is identical across `RegisterRequest`, `AuthRepository.register`, `RegisterUseCase.invoke`, `RegisterViewModel.register()`'s call site, and every test's mock/fixture setup.
- **No placeholders:** every step has complete, runnable code and exact commands.
