package com.trafficwatch.server.config

import com.trafficwatch.server.auth.JwtAuthFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * Stateless JSON API security: no sessions, no CSRF (CSRF protects browser/cookie-based
 * form flows - this API is authenticated purely via a bearer JWT on every request, so
 * there is no session cookie for CSRF to exploit). `/auth/register` and `/auth/login`
 * are the only endpoints reachable without a token; everything else requires
 * [JwtAuthFilter] to have populated the security context with a valid user id.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthFilter: JwtAuthFilter,
) {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            // Anonymous authentication is disabled so that an unauthenticated request
            // leaves the security context's Authentication genuinely null. Left enabled
            // (Spring Security's default), an AnonymousAuthenticationToken counts as
            // "authenticated" for exception-routing purposes, and a request lacking a
            // valid bearer token would be rejected with 403 (AccessDeniedHandler) instead
            // of the 401 (AuthenticationEntryPoint) the API contract requires.
            .anonymous { it.disable() }
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .authorizeHttpRequests { authorize ->
                authorize
                    .requestMatchers("/auth/register", "/auth/login").permitAll()
                    .anyRequest().authenticated()
            }
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }
}
