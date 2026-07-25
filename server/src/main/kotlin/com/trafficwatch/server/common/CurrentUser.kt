package com.trafficwatch.server.common

import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * Resolves "the current authenticated user" for any endpoint behind
 * `SecurityConfig`'s `anyRequest().authenticated()` rule.
 *
 * `com.trafficwatch.server.auth.JwtAuthFilter` populates the
 * [SecurityContextHolder]'s `Authentication` with the user's [UUID] as the principal
 * directly (`UsernamePasswordAuthenticationToken(userId, null, emptyList())`), not a
 * `UserDetails`/username - so callers read it back the same way here. By the time a
 * `@RestController` method runs, Spring Security's `authenticated()` rule has already
 * guaranteed this is set and valid (an invalid/missing token 401s before dispatch), so
 * [id] does not need to handle a null/missing principal.
 */
object CurrentUser {
    fun id(): UUID = SecurityContextHolder.getContext().authentication.principal as UUID
}
