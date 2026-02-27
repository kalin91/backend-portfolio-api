package com.demo.portfolio.api.config;

import com.demo.portfolio.api.dto.CredentialDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spring Security configuration for the portfolio API.
 *
 * <p>
 * Secures all GraphQL operations using HTTP Basic authentication with three in-memory
 * principals loaded from the {@code API_CREDENTIALS_JSON} environment variable:
 * <ul>
 * <li>{@code admin} key → {@code ROLE_ADMIN, ROLE_WRITER, ROLE_READER} – full CRUD access (permissions bitmask 7)</li>
 * <li>{@code writer} key → {@code ROLE_WRITER, ROLE_READER} – create/read/update access (permissions bitmask 6)</li>
 * <li>{@code reader} key → {@code ROLE_READER} – read-only access (permissions bitmask 4)</li>
 * </ul>
 *
 * <p>
 * The following paths are publicly accessible without authentication:
 * <ul>
 * <li>{@code /actuator/health} and {@code /actuator/info} – liveness / readiness probes</li>
 * <li>{@code /graphiql/**} – in-browser GraphQL IDE (dev convenience)</li>
 * </ul>
 *
 * <p>
 * CSRF protection is disabled because the API is stateless (no session cookies are issued).
 * Credentials are loaded at startup from {@link SecurityProperties}, which in turn reads
 * the {@code API_CREDENTIALS_JSON} environment variable (with a local-dev default in
 * {@code application.yml}).
 */
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    /**
     * Configures the reactive HTTP security filter chain.
     *
     * <p>CORS is enabled and delegates to the {@link #corsConfigurationSource(List)} bean.
     *
     * @param http the {@link ServerHttpSecurity} builder provided by Spring Security
     * @return a fully built {@link SecurityWebFilterChain}
     */
    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
            // Enable CORS so cross-origin demo pages (portfolio site, GraphiQL wrapper)
            // can reach the API. Delegates to the CorsConfigurationSource bean below.
            .cors(Customizer.withDefaults())
            // CSRF protection is intentionally disabled: the API is fully stateless and
            // uses HTTP Basic Auth (no session cookies are issued), so CSRF attacks
            // cannot be mounted against it.
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            // Allow embedding in iframes (e.g. for creating a custom GraphiQL wrapper or demo page)
            .headers(headers -> headers.frameOptions(ServerHttpSecurity.HeaderSpec.FrameOptionsSpec::disable))
            .httpBasic(httpBasic -> httpBasic
                // INFO: Suppress the WWW-Authenticate header to prevent the browser's native login popup.
                // Clients (like our GraphiQL demo wrapper) must send credentials proactively.
                .authenticationEntryPoint((exchange, ex) -> {
                    exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                }))
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/actuator/health", "/actuator/info").permitAll()
                .pathMatchers("/graphiql/**").permitAll()
                .anyExchange().authenticated())
            .build();
    }

    /**
     * Provides the CORS configuration source for the security filter chain.
     *
     * <p>Allowed origins are read from the {@code security.cors.allowed-origins} property,
     * which defaults to an empty list (CORS effectively disabled) unless explicitly set.
     * The allowed methods and headers are configured to support the GraphQL endpoint
     * ({@code POST} with {@code Content-Type} and {@code Authorization} headers).
     *
     * @param allowedOrigins the list of allowed origins injected from configuration
     * @return a {@link CorsConfigurationSource} applied to all paths
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${security.cors.allowed-origins:}") List<String> allowedOrigins) {

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(Arrays.asList("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * Creates the in-memory user store from the credentials parsed by {@link SecurityProperties}.
     *
     * <p>
     * Roles are derived from the {@code permissions} bitmask field in each credential entry:
     * <ul>
     * <li>bit 1 ({@code ADMIN}) → {@code ROLE_ADMIN}</li>
     * <li>bit 2 ({@code WRITER}) → {@code ROLE_WRITER}</li>
     * <li>bit 4 ({@code READER}) → {@code ROLE_READER}</li>
     * </ul>
     * For example, an {@code admin} entry with {@code permissions=7} receives all three roles.
     *
     * @param securityProperties the {@link SecurityProperties} bean holding the raw credentials JSON
     * @param objectMapper the Jackson {@link ObjectMapper} used to deserialize the JSON
     * @param encoder the {@link PasswordEncoder} used to hash passwords before storage
     * @return a {@link MapReactiveUserDetailsService} holding all configured principals
     */
    @Bean
    public MapReactiveUserDetailsService userDetailsService(
        SecurityProperties securityProperties,
        ObjectMapper objectMapper,
        PasswordEncoder encoder) {

        Map<String, CredentialDto> credentials = securityProperties.parseCredentials(objectMapper);

        List<UserDetails> users = credentials.entrySet().stream()
            .map(entry -> {
                CredentialDto cred = entry.getValue();
                Set<Permission> perms = Permission.fromMask(cred.permissions());
                String[] roles = perms.stream().map(Permission::name).toArray(String[]::new);
                return User.builder()
                    .username(cred.user())
                    .password(encoder.encode(cred.pass()))
                    .roles(roles)
                    .build();
            })
            .toList();

        if (users.isEmpty()) {
            return new MapReactiveUserDetailsService(User.withUsername("cds_user")
                    .password(encoder.encode("cds_pass"))
                    .roles("NONE")
                    .build());
        }

        return new MapReactiveUserDetailsService(users);
    }

    /**
     * Provides a {@link BCryptPasswordEncoder} for hashing in-memory user passwords.
     *
     * @return the configured {@link PasswordEncoder}
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

