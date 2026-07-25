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

/**
 * Thrown by AuthService.login() when the email is not registered OR the password does
 * not match the stored hash. Deliberately a single, generic exception for both cases -
 * distinguishing them in the response would leak which registered emails exist. Mapped
 * to HTTP 401 by Task 6's GlobalExceptionHandler eventually; AuthController maps it
 * locally for now.
 */
class InvalidCredentialsException : RuntimeException("Invalid email or password")
