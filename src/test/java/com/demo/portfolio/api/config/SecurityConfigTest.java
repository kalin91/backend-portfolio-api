package com.demo.portfolio.api.config;

import com.demo.portfolio.api.dto.CredentialDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SecurityConfig} and {@link SecurityProperties}.
 *
 * <p>Verifies that the in-memory user store is populated correctly from a JSON credentials
 * string, that the three role profiles ({@code admin}, {@code writer}, {@code reader}) receive
 * the expected role assignments, and that passwords are BCrypt-encoded and never stored as
 * plain text.
 */
@SuppressWarnings("null")
class SecurityConfigTest {

    private static final String CREDENTIALS_JSON =
            "{\"admin\":{\"user\":\"api_admin\",\"pass\":\"admin123\",\"permissions\":7}," +
            "\"writer\":{\"user\":\"api_writer\",\"pass\":\"writer123\",\"permissions\":6}," +
            "\"reader\":{\"user\":\"api_reader\",\"pass\":\"reader123\",\"permissions\":4}}";

    private final SecurityConfig securityConfig = new SecurityConfig();
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Verifies that {@link SecurityConfig#passwordEncoder()} produces a {@link BCryptPasswordEncoder} instance.
     */
    @Test
    void passwordEncoderBeanIsBCrypt() {
        PasswordEncoder passwordEncoder = securityConfig.passwordEncoder();
        assertNotNull(passwordEncoder);
        assertTrue(passwordEncoder instanceof BCryptPasswordEncoder);
    }

    /**
     * Verifies that {@link SecurityProperties#parseCredentials(ObjectMapper)} correctly
     * deserializes the JSON string into a map of {@link CredentialDto} instances keyed
     * by their role profile names.
     */
    @Test
    void securityPropertiesParseCredentials() {
        SecurityProperties props = buildProperties();
        Map<String, CredentialDto> creds = props.parseCredentials(objectMapper);

        assertEquals(3, creds.size());
        assertEquals("api_admin",  creds.get("admin").user());
        assertEquals("api_writer", creds.get("writer").user());
        assertEquals("api_reader", creds.get("reader").user());
    }

    /**
     * Verifies that the {@code admin} principal holds all three roles, allowing it to
     * invoke queries, create/update mutations, and delete mutations.
     */
    @Test
    void adminUserHasAllRoles() {
        MapReactiveUserDetailsService service = buildService();

        UserDetails admin = service.findByUsername("api_admin").block();
        assertNotNull(admin);
        assertTrue(hasRole(admin, "ROLE_ADMIN"));
        assertTrue(hasRole(admin, "ROLE_WRITER"));
        assertTrue(hasRole(admin, "ROLE_READER"));
    }

    /**
     * Verifies that the {@code writer} principal holds {@code ROLE_WRITER} and
     * {@code ROLE_READER} but not {@code ROLE_ADMIN}, restricting it from delete operations.
     */
    @Test
    void writerUserHasWriterAndReaderRoles() {
        MapReactiveUserDetailsService service = buildService();

        UserDetails writer = service.findByUsername("api_writer").block();
        assertNotNull(writer);
        assertFalse(hasRole(writer, "ROLE_ADMIN"));
        assertTrue(hasRole(writer, "ROLE_WRITER"));
        assertTrue(hasRole(writer, "ROLE_READER"));
    }

    /**
     * Verifies that the {@code reader} principal holds only {@code ROLE_READER},
     * restricting it to read-only query operations.
     */
    @Test
    void readerUserHasOnlyReaderRole() {
        MapReactiveUserDetailsService service = buildService();

        UserDetails reader = service.findByUsername("api_reader").block();
        assertNotNull(reader);
        assertFalse(hasRole(reader, "ROLE_ADMIN"));
        assertFalse(hasRole(reader, "ROLE_WRITER"));
        assertTrue(hasRole(reader, "ROLE_READER"));
    }

    /**
     * Verifies that passwords are BCrypt-encoded in the user store and are not stored
     * as plain text, ensuring the raw credential value is never retained in memory.
     */
    @Test
    void passwordsAreEncodedAndNotStoredAsPlainText() {
        MapReactiveUserDetailsService service = buildService();

        UserDetails admin = service.findByUsername("api_admin").block();
        assertNotNull(admin);
        assertNotEquals("admin123", admin.getPassword());
        assertTrue(encoder.matches("admin123", admin.getPassword()));
    }

    /**
     * Verifies that the CORS configuration source exposes the configured allowed origins,
     * methods, and headers, and that credentials are permitted.
     */
    @Test
    void corsConfigurationSourceUsesAllowedOrigins() {
        List<String> origins = Arrays.asList("https://example.com", "https://demo.site");
        CorsConfigurationSource source = securityConfig.corsConfigurationSource(origins);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/model").build());
        CorsConfiguration config = source.getCorsConfiguration(exchange);

        assertNotNull(config);
        assertEquals(origins, config.getAllowedOrigins());
        assertTrue(config.getAllowedMethods().contains("POST"));
        assertTrue(config.getAllowedMethods().contains("OPTIONS"));
        assertTrue(config.getAllowedHeaders().contains("Authorization"));
        assertTrue(config.getAllowedHeaders().contains("Content-Type"));
        assertTrue(config.getAllowCredentials());
        assertEquals(3600L, config.getMaxAge());
    }

    /**
     * Verifies that when no origins are configured (empty list), the CORS source still
     * returns a valid configuration with an empty origin list.
     */
    @Test
    void corsConfigurationSourceWithEmptyOrigins() {
        CorsConfigurationSource source = securityConfig.corsConfigurationSource(Collections.emptyList());

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/model").build());
        CorsConfiguration config = source.getCorsConfiguration(exchange);

        assertNotNull(config);
        assertTrue(config.getAllowedOrigins().isEmpty());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private SecurityProperties buildProperties() {
        SecurityProperties props = new SecurityProperties();
        // Since SecurityProperties now expects a Base64-encoded string, we must encode our test JSON.
        String encoded = java.util.Base64.getEncoder().encodeToString(CREDENTIALS_JSON.getBytes());
        props.setCredentialsJson(encoded);
        return props;
    }

    private MapReactiveUserDetailsService buildService() {
        return securityConfig.userDetailsService(buildProperties(), objectMapper, encoder);
    }

    private boolean hasRole(UserDetails user, String role) {
        return user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(role));
    }
}

