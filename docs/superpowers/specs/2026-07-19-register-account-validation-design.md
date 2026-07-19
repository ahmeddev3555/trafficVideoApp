# Create Account: Field Validation + Phone/CNIC Capture

## Context

The registration flow (`RegisterScreen` → `RegisterViewModel` → `RegisterUseCase` →
`AuthRepository` → `MockApiService`) currently validates only: name not blank, email
not blank (no format check), password length, and password confirmation match. This
spec adds email format validation, two new required fields (Phone Number, CNIC), and
a phone-number uniqueness check enforced by the backend.

## Scope

In scope:
- Email format validation
- Phone Number field: required, Pakistani local format, format validation
- CNIC field: required, auto-formatted with dashes as typed, format validation
- Phone number uniqueness, enforced at submission via the register API call

Out of scope (explicitly deferred):
- Persisting phone/CNIC to the local `User` model or `TokenStore` — captured on the
  form and sent to the register API only
- Live "check availability" as-you-type for phone number — uniqueness is only
  discovered when the form is submitted
- Email uniqueness checking
- CNIC uniqueness checking

## Validation rules

All rules follow the existing pattern in `RegisterViewModel.register()`: a `when`
chain of blank/format checks, first failure wins, sets `uiState.error`, shown via
the existing Snackbar. Order (top to bottom, matching field order in the form):

1. Name not blank (existing)
2. Phone number not blank; format `^03\d{9}$` (11 digits, starts `03`) →
   *"Enter a valid phone number (e.g. 03001234567)"*
3. CNIC not blank; format 13 raw digits (`^\d{13}$`) →
   *"Enter a valid 13-digit CNIC"*
4. Email not blank (existing); format
   `^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$` →
   *"Enter a valid email address"*
5. Password length ≥ 8 (existing)
6. Password == Confirm Password (existing)

Phone number uniqueness (see below) is **not** part of this client-side `when`
chain — it can only be discovered via the register API call itself, so it surfaces
through the existing `onFailure` path after submission, not as a pre-submit check.

## Form UI changes (`RegisterScreen.kt`)

New field order:

```
Full Name → Phone Number → CNIC → Email → Password → Confirm Password
```

- **Phone Number**: `KeyboardType.Phone`, input filtered to digits-only as typed,
  capped at 11 characters. Label/placeholder hints the expected format
  (e.g. "Phone Number (03XXXXXXXXX)").
- **CNIC**: input filtered to digits-only as typed, capped at 13 characters
  (raw, unformatted value held in state). A `VisualTransformation` renders the
  raw digits grouped 5-7-1 with dashes after the 5th and 12th digits
  (`12345-1234567-1`) for display only — the underlying state stays raw digits.
- IME "Next" chaining extends through the two new fields in the order above;
  Confirm Password keeps its existing "Done" → `register()` behavior.

## Data flow changes

- **`RegisterUiState`**: add `phoneNumber: String = ""`, `cnic: String = ""` (raw
  digits, unformatted).
- **`RegisterViewModel`**: add `onPhoneNumberChange`/`onCnicChange` (digit-filter +
  length cap on each keystroke), extend the `when` validation chain per above.
- **`RegisterUseCase`**: add `phoneNumber`, `cnic` parameters, passed straight
  through to `AuthRepository.register`.
- **`AuthRepository.register(...)`**: add `phoneNumber`, `cnic` parameters,
  included in the `RegisterRequest` sent to the API. Not added to the returned
  `User` or to `TokenStore` (out of scope, per above).
- **`RegisterRequest` DTO** (`AuthDtos.kt`): add `phoneNumber`, `cnic` fields.
- **`ApiService.register`**: no signature change needed — it already takes the
  whole `RegisterRequest` object.

## Phone number uniqueness (`MockApiService`)

`MockApiService` gains an in-memory registry:

```kotlin
private val registeredPhoneNumbers = ConcurrentHashMap.newKeySet<String>()
```

In `register()`, after the existing fake delay: if the submitted phone number is
already present, throw `DuplicatePhoneNumberException("An account with this phone
number already exists.")` instead of returning a success response; otherwise add
the number to the set and proceed as today (generate token/user, return
`AuthResponse`). `DuplicatePhoneNumberException` is a small `Exception` subclass
defined alongside `MockApiService` in the same file — no shared exceptions module
exists elsewhere in the codebase, so this avoids introducing one for a single use.

This registry persists only for the app process's lifetime (mock has no real
storage) — sufficient for manually testing the duplicate-detection path within a
session.

No new UI/error-handling code is needed: `AuthRepository.register` already wraps
the API call in `runCatching`, so the exception becomes a `Result.failure`, and
`RegisterViewModel`'s existing `onFailure` branch puts `e.message` into
`uiState.error`, shown via the existing Snackbar — identical to every other
registration failure today.

## Testing / verification

No test source files exist in this project yet, but `build.gradle.kts` already
wires up JUnit, MockK, Turbine, and `kotlinx-coroutines-test` under
`testImplementation` — unused until now. These are a clean fit for the pure-logic
pieces added here (no Android framework dependency), so this feature adds the
project's first unit tests:

- `RegisterViewModel`'s validation chain (email/phone/CNIC blank + format checks,
  existing checks still pass, success and duplicate-phone failure paths)
- The CNIC dash-formatting function
- `MockApiService`'s duplicate-phone-number detection

The Compose UI wiring itself (new fields, `VisualTransformation` rendering,
IME/focus chaining) is not unit tested — verified manually by running the app and
exercising the form, consistent with how the rest of the UI in this codebase is
verified today.
