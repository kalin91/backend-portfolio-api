package com.demo.portfolio.api.controller;

import com.demo.portfolio.api.config.SecurityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GraphiQLProxyController}.
 *
 * <p>All WebClient interactions are replaced with deterministic exchange-function stubs so no real HTTP server
 * is required. The reactive return values are resolved with {@code .block()} as permitted
 * by the project's testing guidelines.
 */
@ExtendWith(MockitoExtension.class)
class GraphiQLProxyControllerTest {

    private static final String CREDENTIALS_JSON =
            "{\"admin\":{\"user\":\"api_admin\",\"pass\":\"admin123\",\"permissions\":7}," +
            "\"writer\":{\"user\":\"api_writer\",\"pass\":\"writer123\",\"permissions\":6}," +
            "\"reader\":{\"user\":\"api_reader\",\"pass\":\"reader123\",\"permissions\":4}}";

    private static final String GRAPHIQL_HTML =
            "<html><head><title>GraphiQL</title>" +
            "<script>const a=params.get(\"path\") || \"/graphql\";" +
            "const b=params.get(\"wsPath\") || \"/graphql\";</script>" +
            "</head><body>GraphiQL UI</body></html>";

    // ── proxyGraphiQL – happy paths ─────────────────────────────────────────

    /**
     * Verifies that a valid {@code admin} role returns GraphiQL HTML with injected auth script
     * inserted before {@code </head>} and preserving original content.
     */
    @Test
    void proxyGraphiQLReturnsModifiedHtmlForAdminRole() {
        GraphiQLProxyController controller = buildController(webClientWithHtmlResponse(HttpStatus.OK, GRAPHIQL_HTML));

        String result = controller.proxyGraphiQL("admin", requestFor("admin")).block();

        assertNotNull(result);
        assertTrue(result.contains("<script>"), "Result should contain the injected auth script");
        assertTrue(result.contains("<title>GraphiQL</title>"), "Result should retain original HTML content");
        assertTrue(result.indexOf("<script>") < result.indexOf("</head>"),
                "Injected script should appear before </head>");
    }

    /**
     * Verifies that GraphiQL default HTTP and WS paths are aligned to the configured
     * GraphQL endpoint path when no path query parameter is provided.
     */
    @Test
    void proxyGraphiQLAlignsDefaultPathsToConfiguredGraphqlPath() {
        GraphiQLProxyController controller = buildController(webClientWithHtmlResponse(HttpStatus.OK, GRAPHIQL_HTML));

        String result = controller.proxyGraphiQL("admin", requestFor("admin")).block();

        assertNotNull(result);
        assertTrue(result.contains("params.get(\"path\") || \"/model\""),
                "HTTP default path should be rewritten to the configured GraphQL path");
        assertTrue(result.contains("params.get(\"wsPath\") || \"/model\""),
                "WS default path should be rewritten to the configured GraphQL path");
    }

    /**
     * Verifies that the injected auth header uses the correct Base64 encoding for
     * the role credentials loaded from configuration.
     */
    @Test
    void proxyGraphiQLInjectsCorrectBase64TokenForReader() {
        GraphiQLProxyController controller = buildController(webClientWithHtmlResponse(HttpStatus.OK, GRAPHIQL_HTML));

        String result = controller.proxyGraphiQL("reader", requestFor("reader")).block();

        String expected = Base64.getEncoder().encodeToString("api_reader:reader123".getBytes());
        assertNotNull(result);
        assertTrue(result.contains(expected),
                "Injected script should contain the correct Base64 token for the reader role");
    }

    // ── proxyGraphiQL – error paths ─────────────────────────────────────────

    /**
     * Verifies that an unknown role triggers a {@code 400 Bad Request} response status exception.
     */
    @Test
    void proxyGraphiQLReturnsBadRequestForUnknownRole() {
        GraphiQLProxyController controller = buildController(webClientWithHtmlResponse(HttpStatus.OK, GRAPHIQL_HTML));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> blockProxyCall(controller, "superuser"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        String reason = ex.getReason();
        assertNotNull(reason);
        assertTrue(reason.contains("superuser"),
                "Error reason should mention the invalid role name");
    }

    /**
     * Verifies that a downstream failure from the WebClient call is mapped to a
     * {@code 502 Bad Gateway} response status exception.
     */
    @Test
    void proxyGraphiQLReturnsBadGatewayWhenWebClientFails() {
        GraphiQLProxyController controller = buildController(webClientWithError(new RuntimeException("Connection refused")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> blockProxyCall(controller, "admin"));

        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Executes the public proxy API and blocks for the HTML response.
     *
     * @param controller controller under test
     * @param role credential profile key
     * @return rendered GraphiQL HTML
     */
    private String blockProxyCall(GraphiQLProxyController controller, String role) {
        return controller.proxyGraphiQL(role, requestFor(role)).block();
    }

    /**
     * Builds a controller with deterministic credentials and GraphQL path for unit tests.
     *
     * @param webClient web client instance used to fetch upstream GraphiQL HTML
     * @return controller configured for standalone unit testing
     */
    private GraphiQLProxyController buildController(WebClient webClient) {
        GraphiQLProxyController controller = new GraphiQLProxyController(buildProperties(), new ObjectMapper(), webClient);
        ReflectionTestUtils.setField(controller, "graphqlPath", "/model");
        return controller;
    }

    /**
     * Creates a request matching the runtime endpoint contract used by the proxy method.
     *
     * @param role credential profile key passed as query parameter
     * @return server request with role query parameter
     */
    private MockServerHttpRequest requestFor(String role) {
        return MockServerHttpRequest.get("http://localhost/proxy/graphiql?role=" + role).build();
    }

    /**
     * Creates a {@link WebClient} returning a fixed HTTP status and HTML body.
     *
     * @param status status to return from the mocked exchange function
     * @param body HTML body to return
     * @return web client backed by a deterministic exchange function
     */
    private WebClient webClientWithHtmlResponse(HttpStatus status, String body) {
        ExchangeFunction exchangeFunction = request -> Mono.just(ClientResponse.create(Objects.requireNonNull(status))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
            .body(Objects.requireNonNull(body))
                .build());
        return WebClient.builder().exchangeFunction(exchangeFunction).build();
    }

    /**
     * Creates a {@link WebClient} that fails the exchange with the provided exception.
     *
     * @param throwable exception to emit during exchange
     * @return web client backed by a failing exchange function
     */
    private WebClient webClientWithError(Throwable throwable) {
        ExchangeFunction exchangeFunction = request -> Mono.error(throwable);
        return WebClient.builder().exchangeFunction(exchangeFunction).build();
    }

    /**
     * Builds credential properties containing encoded role-based test credentials.
     *
     * @return security properties initialized with encoded credentials JSON
     */
    private SecurityProperties buildProperties() {
        SecurityProperties props = new SecurityProperties();
        String encoded = Base64.getEncoder().encodeToString(CREDENTIALS_JSON.getBytes());
        props.setCredentialsJson(encoded);
        return props;
    }
}
