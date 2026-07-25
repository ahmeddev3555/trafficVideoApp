package com.trafficwatch.server.auth.exception

/**
 * Thrown by AuthService.register() when the requested phone number is already
 * registered. Mapped to HTTP 409 by Task 6's GlobalExceptionHandler - not this task.
 */
class DuplicatePhoneNumberException(phoneNumber: String) :
    RuntimeException("Phone number already registered: $phoneNumber")

/**
 * Thrown by AuthService.register() when the requested email is already registered.
 * Mapped to HTTP 409 by Task 6's GlobalExceptionHandler - not this task.
 */
class DuplicateEmailException(email: String) :
    RuntimeException("Email already registered: $email")
