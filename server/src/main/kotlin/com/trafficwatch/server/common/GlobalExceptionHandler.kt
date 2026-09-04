package com.trafficwatch.server.common

import com.trafficwatch.server.auth.exception.DuplicateEmailException
import com.trafficwatch.server.auth.exception.DuplicatePhoneNumberException
import com.trafficwatch.server.auth.exception.InvalidCredentialsException
import com.trafficwatch.server.reports.exception.InvalidPaginationException
import com.trafficwatch.server.reports.exception.ReportNotFoundException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.time.format.DateTimeParseException

/**
 * App-wide mapping from exceptions to a uniform [ApiError]`{error, message}` response
 * body, applied across every `@RestController` (not just `auth/`'s) - future features
 * (e.g. reports) add their own `@ExceptionHandler` methods here rather than building a
 * second, feature-local advice class.
 *
 * Handler ordering note: Spring resolves `@ExceptionHandler` methods by most-specific
 * exception type first, so [DuplicatePhoneNumberException]/[DuplicateEmailException]
 * (both plain `RuntimeException` subclasses, unrelated to
 * [DataIntegrityViolationException]) are matched independently of the
 * [DataIntegrityViolationException] fallback below - there's no ambiguity between them,
 * since neither is a supertype of the other. The fallback exists purely to catch the
 * case where AuthService's check-then-insert race lets a raw DB constraint violation
 * through instead of one of the friendlier typed exceptions.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(DuplicatePhoneNumberException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleDuplicatePhoneNumber(ex: DuplicatePhoneNumberException): ApiError =
        ApiError(error = "DUPLICATE_PHONE_NUMBER", message = ex.message ?: "Phone number already registered")

    @ExceptionHandler(DuplicateEmailException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleDuplicateEmail(ex: DuplicateEmailException): ApiError =
        ApiError(error = "DUPLICATE_EMAIL", message = ex.message ?: "Email already registered")

    /**
     * Safety net for the TOCTOU race documented on `AuthService.register()`: two
     * concurrent registrations can both pass the existence checks and race to save(),
     * with the loser hitting the DB's unique constraint directly rather than either
     * typed exception above. The DB message is not surfaced verbatim (it can include
     * raw constraint/column names) - a generic, safe message is returned instead.
     */
    @ExceptionHandler(DataIntegrityViolationException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleDataIntegrityViolation(ex: DataIntegrityViolationException): ApiError =
        ApiError(error = "DUPLICATE_RESOURCE", message = "A record with the same unique value already exists")

    @ExceptionHandler(InvalidCredentialsException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun handleInvalidCredentials(ex: InvalidCredentialsException): ApiError =
        ApiError(error = "INVALID_CREDENTIALS", message = ex.message ?: "Invalid email or password")

    /**
     * Thrown by `ReportService.getStatus()` when the requested report either belongs to a
     * different user or does not exist at all - both cases are indistinguishable 404s so
     * a foreign report id never leaks whether it exists for someone else.
     */
    @ExceptionHandler(ReportNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleReportNotFound(ex: ReportNotFoundException): ApiError =
        ApiError(error = "REPORT_NOT_FOUND", message = ex.message ?: "Report not found")

    /**
     * Thrown by `@Valid` on a `@RequestBody` when `jakarta.validation` constraints fail
     * (e.g. malformed phone number, too-short password). Only the first field error's
     * message is surfaced - good enough to point the client at the problem without
     * building a structured multi-field error shape this app doesn't need yet.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleValidationError(ex: MethodArgumentNotValidException): ApiError {
        val message = ex.bindingResult.fieldErrors.firstOrNull()?.defaultMessage
            ?: "Validation failed"
        return ApiError(error = "VALIDATION_ERROR", message = message)
    }

    /**
     * Thrown by Spring's argument-resolution machinery when a `@RequestParam` can't be
     * converted to its declared type - e.g. `GET /reports?status=BOGUS` against
     * `ReportController.listReports`'s `status: ReportStatus?` param. Without this
     * handler the request would fall through to Spring Boot's default `/error` body
     * shape instead of this API's uniform [ApiError].
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleMethodArgumentTypeMismatch(ex: MethodArgumentTypeMismatchException): ApiError {
        val allowedValues = ex.requiredType?.enumConstants
            ?.joinToString(", ") { it.toString() }
        val message = if (allowedValues != null) {
            "Invalid value for parameter '${ex.name}'; allowed values: $allowedValues"
        } else {
            "Invalid value for parameter '${ex.name}'"
        }
        return ApiError(error = "INVALID_PARAMETER", message = message)
    }

    /**
     * Thrown by `ReportService.listReports()` when `page` or `page_size` is less than 1 -
     * e.g. `GET /reports?page=0`. Without this handler, an out-of-range `page` would
     * otherwise reach `PageRequest.of` as a negative index, throwing a plain
     * `IllegalArgumentException` with no handler here and falling through to Spring Boot's
     * default `/error` body instead of this API's uniform [ApiError].
     */
    @ExceptionHandler(InvalidPaginationException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleInvalidPagination(ex: InvalidPaginationException): ApiError =
        ApiError(error = "INVALID_PAGINATION", message = ex.message ?: "Invalid pagination parameters")

    /**
     * Thrown by ReportService.submit()'s recorded_at parse (legacy literal-Z or the
     * marked real-UTC path) when the client sends an unparseable timestamp. Without this
     * it falls through to a 500.
     */
    @ExceptionHandler(DateTimeParseException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleDateTimeParseError(ex: DateTimeParseException): ApiError =
        ApiError(error = "INVALID_PARAMETER", message = "Invalid timestamp format for 'recorded_at'")
}
