package com.trafficwatch.server.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Date
import java.util.UUID

class JwtServiceTest {

    // Test-only secret. Long enough (well over 32 bytes) to satisfy jjwt's HS256 minimum
    // key-length requirement. Never use a value like this in a real/shared environment -
    // real secrets live in application-local.yml (gitignored) or env-supplied config.
    private val testSecret =
        "unit-test-only-jwt-signing-secret-do-not-use-in-any-real-environment-0123456789"

    private val jwtService = JwtService(secret = testSecret, expirationDays = 30)

    @Test
    fun `generateToken then extractUserId round-trips the same user id`() {
        val userId = UUID.randomUUID()

        val token = jwtService.generateToken(userId)

        assertThat(jwtService.extractUserId(token)).isEqualTo(userId)
        assertThat(jwtService.isValid(token)).isTrue()
    }

    @Test
    fun `tampered token is rejected without throwing`() {
        val token = jwtService.generateToken(UUID.randomUUID())

        // Flip the second-to-last character of the token (i.e. of the base64url-encoded
        // signature segment) so the signature no longer matches, while keeping the string
        // well-formed (three dot-separated segments). We deliberately avoid the very last
        // character: a 256-bit HMAC-SHA256 signature base64url-encodes to 43 characters,
        // and since 256 isn't a multiple of 6, that final character's 6-bit group carries
        // only 4 real signature bits plus 2 unused padding bits - flipping it can sometimes
        // change only the padding, leaving the decoded signature bytes unchanged and making
        // the test flaky. The second-to-last character is a full 6-bit group of real
        // signature bits, so flipping it is guaranteed to change the decoded signature.
        val position = token.length - 2
        val originalChar = token[position]
        val flipped = if (originalChar == 'A') 'B' else 'A'
        val tampered = token.substring(0, position) + flipped + token.substring(position + 1)

        assertThat(jwtService.isValid(tampered)).isFalse()
        assertThat(jwtService.extractUserId(tampered)).isNull()
    }

    @Test
    fun `token signed with a different key is rejected`() {
        val otherKey = Keys.hmacShaKeyFor(
            "a-completely-different-unit-test-only-signing-secret-value-000".toByteArray(),
        )
        val foreignToken = Jwts.builder()
            .subject(UUID.randomUUID().toString())
            .signWith(otherKey)
            .compact()

        assertThat(jwtService.isValid(foreignToken)).isFalse()
        assertThat(jwtService.extractUserId(foreignToken)).isNull()
    }

    @Test
    fun `expired token is rejected without throwing or sleeping`() {
        // Manually construct an already-expired token (backdated issuedAt/expiration)
        // signed with the SAME secret, rather than waiting on a real clock or shrinking
        // expiration-days to a fractional value that's awkward at day granularity.
        val key = Keys.hmacShaKeyFor(testSecret.toByteArray(Charsets.UTF_8))
        val issuedAt = Date(System.currentTimeMillis() - Duration.ofDays(31).toMillis())
        val expiredAt = Date(System.currentTimeMillis() - Duration.ofDays(1).toMillis())
        val expiredToken = Jwts.builder()
            .subject(UUID.randomUUID().toString())
            .issuedAt(issuedAt)
            .expiration(expiredAt)
            .signWith(key)
            .compact()

        assertThat(jwtService.isValid(expiredToken)).isFalse()
        assertThat(jwtService.extractUserId(expiredToken)).isNull()
    }

    @Test
    fun `malformed garbage string is rejected without throwing`() {
        assertThat(jwtService.isValid("not-a-jwt-at-all")).isFalse()
        assertThat(jwtService.extractUserId("not-a-jwt-at-all")).isNull()
    }
}
