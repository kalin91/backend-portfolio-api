package com.demo.portfolio.api.config;

import com.demo.portfolio.api.dto.CredentialDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SecurityProperties}.
 */
@ExtendWith(MockitoExtension.class)
class SecurityPropertiesTest {

    @Test
    void setCredentialsJsonDecodesBase64IntoRawJson() {
        SecurityProperties properties = new SecurityProperties();
        String rawJson = "{\"reader\":{\"user\":\"api_reader\",\"pass\":\"reader123\",\"permissions\":4}}";
        String encoded = Base64.getEncoder().encodeToString(rawJson.getBytes(StandardCharsets.UTF_8));

        properties.setCredentialsJson(encoded);

        assertEquals(rawJson, properties.getCredentialsJson());
    }

    @Test
    void parseCredentialsReturnsMappedEntries() {
        SecurityProperties properties = new SecurityProperties();
        String rawJson = "{" +
                "\"admin\":{\"user\":\"api_admin\",\"pass\":\"admin123\",\"permissions\":7}," +
                "\"writer\":{\"user\":\"api_writer\",\"pass\":\"writer123\",\"permissions\":6}," +
                "\"reader\":{\"user\":\"api_reader\",\"pass\":\"reader123\",\"permissions\":4}" +
                "}";
        String encoded = Base64.getEncoder().encodeToString(rawJson.getBytes(StandardCharsets.UTF_8));
        properties.setCredentialsJson(encoded);

        Map<String, CredentialDto> credentials = properties.parseCredentials(new ObjectMapper());

        assertNotNull(credentials);
        assertEquals(3, credentials.size());
        assertEquals("api_admin", credentials.get("admin").user());
        assertEquals("api_writer", credentials.get("writer").user());
        assertEquals("api_reader", credentials.get("reader").user());
    }

    @Test
    void parseCredentialsThrowsIllegalStateExceptionWhenJsonIsInvalid() {
        SecurityProperties properties = new SecurityProperties();
        String invalidJson = "{\"admin\":{\"user\":\"api_admin\"";
        String encoded = Base64.getEncoder().encodeToString(invalidJson.getBytes(StandardCharsets.UTF_8));
        properties.setCredentialsJson(encoded);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> properties.parseCredentials(new ObjectMapper())
        );

        assertTrue(exception.getMessage().contains("Failed to parse security.credentials-json"));
    }
}
