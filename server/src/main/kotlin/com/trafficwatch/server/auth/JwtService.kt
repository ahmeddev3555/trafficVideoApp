package com.trafficwatch.server.auth

import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

/**
 * Issues and validates HMAC-SHA signed JWTs carrying a user's id as the `subject` claim.
 *
 * The signing secret MUST be supplied via `app.jwt.secret` with no default value here -
 * baking a real secret into version-controlled `application.yml` would be a security bug,
 * so a missing property fails application startup fast instead of silently running with
 * an absent/guessable secret. See `application-local.yml.example` for the local dev
 * template (gitignored once copied to `application-local.yml`) and
 * `src/test/resources/application-test.yml` for the test-only secret used by the test
 * suite.
 */
@Service
class JwtService(
    @Value("\${app.jwt.secret}")
    secret: String,
    @Value("\${app.jwt.expiration-days:30}")
    private val expirationDays: Long = 30,
) {

    private val key: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray(Charsets.UTF_8))

    /** Issues a signed JWT whose subject claim is [userId], expiring in [expirationDays]. */
    fun generateToken(userId: UUID): String {
        val issuedAt = Date()
        val expiresAt = Date(issuedAt.time + Duration.ofDays(expirationDays).toMillis())

        return Jwts.builder()
            .subject(userId.toString())
            .issuedAt(issuedAt)
            .expiration(expiresAt)
            .signWith(key)
            .compact()
    }

    /**
     * Returns the user id encoded in [token]'s subject claim, or null if [token] is
     * missing, malformed, tampered, expired, or otherwise fails verification. Never throws
     * - callers (e.g. an HTTP auth filter) can treat null as "not authenticated" without
     * needing a try/catch of their own.
     */
    fun extractUserId(token: String): UUID? {
        val subject = parseClaims(token)?.subject ?: return null
        return runCatching { UUID.fromString(subject) }.getOrNull()
    }

    /** True only if [token]'s signature verifies against our key and it has not expired. */
    fun isValid(token: String): Boolean = parseClaims(token) != null

    private fun parseClaims(token: String): Claims? =
        try {
            Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .payload
        } catch (e: JwtException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
}
