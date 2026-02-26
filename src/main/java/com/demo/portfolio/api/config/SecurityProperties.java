package com.demo.portfolio.api.config;

import com.demo.portfolio.api.dto.CredentialDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

/**
 * Typed configuration properties that bind the {@code security.credentials-json}
 * property from {@code application.yml}.
 *
 * <p>The property value is expected to be a JSON object whose keys are role profiles
 * ({@code admin}, {@code writer}, {@code reader}) and whose values each carry a
 * {@code user} / {@code pass} pair, for example:
 *
 * <pre>{@code
 * {
 *   "admin":  { "user": "api_admin",  "pass": "...", "permissions": 7 },
 *   "writer": { "user": "api_writer", "pass": "...", "permissions": 6 },
 *   "reader": { "user": "api_reader", "pass": "...", "permissions": 4 }
 * }
 * }</pre>
 *
 * <p>In production, supply the JSON via the {@code API_CREDENTIALS_JSON} environment
 * variable so that no real credentials are ever committed to source control.
 * A safe local-dev default is embedded directly in {@code application.yml}.
 */
@Component
@ConfigurationProperties(prefix = "security")
@Slf4j
@Validated
public class SecurityProperties {

    @NotBlank
    private String credentialsJson;

    /**
     * Returns the raw JSON string containing all API credentials.
     *
     * @return the credentials JSON string as configured
     */
    public String getCredentialsJson() {
        return credentialsJson;
    }

    /**
     * Sets the raw JSON string containing all API credentials.
     * Called automatically by Spring's {@link ConfigurationProperties} binding.
     *
     * @param credentialsJson the JSON string to bind
     */
    public void setCredentialsJson(String credentialsJson) {
        this.credentialsJson = credentialsJson;
    }

    /**
     * Parses the raw JSON string into a map of role-profile keys to
     * {@link CredentialDto} instances.
     *
     * @param objectMapper the Jackson {@link ObjectMapper} used for deserialization
     * @return a map whose keys are role profiles ({@code admin}, {@code writer},
     *         {@code reader}) and whose values are the corresponding credential pairs
     * @throws IllegalStateException if the JSON cannot be parsed
     */
    public Map<String, CredentialDto> parseCredentials(ObjectMapper objectMapper) {
        try {
            return objectMapper.readValue(credentialsJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Parsing credentials JSON failed - ensure API_CREDENTIALS_JSON is valid JSON {}", credentialsJson, e);  
            throw new IllegalStateException("Failed to parse security.credentials-json – " +
                    "ensure API_CREDENTIALS_JSON is valid JSON", e);
        }
    }
}
