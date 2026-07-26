package com.trafficwatch.server.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

private const val BEARER_PREFIX = "Bearer "

/**
 * Reads a `Authorization: Bearer <token>` header, and if it carries a valid, unexpired
 * JWT (per [JwtService]), populates the [SecurityContextHolder] with an authenticated
 * principal (the user's [java.util.UUID]) so later handlers/`@AuthenticationPrincipal`
 * can resolve "the current user" (needed starting Task 7's report endpoints).
 *
 * Deliberately never throws: a missing header, a malformed header, or an invalid/expired
 * token all just leave the security context unauthenticated. It is
 * [org.springframework.security.config.annotation.web.builders.HttpSecurity]'s
 * `authenticated()` rule on `anyRequest()` (wired in SecurityConfig) - not this filter -
 * that turns "unauthenticated" into an HTTP 401 response.
 */
@Component
class JwtAuthFilter(
    private val jwtService: JwtService,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader("Authorization")

        if (header != null && header.startsWith(BEARER_PREFIX)) {
            val token = header.removePrefix(BEARER_PREFIX).trim()

            if (jwtService.isValid(token)) {
                val userId = jwtService.extractUserId(token)

                if (userId != null && SecurityContextHolder.getContext().authentication == null) {
                    val authentication = UsernamePasswordAuthenticationToken(userId, null, emptyList())
                    SecurityContextHolder.getContext().authentication = authentication
                }
            }
        }

        filterChain.doFilter(request, response)
    }
}
