package com.demo.portfolio.api.controller;

import com.demo.portfolio.api.config.SecurityProperties;
import com.demo.portfolio.api.dto.CredentialDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.lang.NonNull;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/**
 * Proxies the public GraphiQL page and injects role-based Basic authentication for GraphQL calls.
 *
 * <p>
 * This endpoint keeps credentials server-side: the browser requests
 * {@code /proxy/graphiql?role=<role>} and receives GraphiQL HTML with an injected script
 * that appends the {@code Authorization} header for requests targeting the configured GraphQL path.
 */
@RestController
@Slf4j
public class GraphiQLProxyController {

    private final WebClient webClient;

    @Value("${dgs.graphql.path:/graphql}")
    private String graphqlPath;

    private Map<String, CredentialDto> credentials;

    /**
     * Creates a proxy controller with required collaborators.
     *
     * @param securityProperties provides configured credentials by role
     * @param objectMapper maps credential JSON to typed DTOs
     * @param webClient fetches the underlying {@code /graphiql} HTML page
     */
    public GraphiQLProxyController(
        SecurityProperties securityProperties,
        ObjectMapper objectMapper,
        WebClient webClient) {
        this.webClient = webClient;
        this.credentials = securityProperties.parseCredentials(objectMapper);
    }

    /**
     * Returns GraphiQL HTML with an injected script that adds role-based authentication headers.
     *
     * @param role credential profile key such as {@code admin}, {@code writer}, or {@code reader}
     * @param request incoming HTTP request used to resolve the current host and port
     * @return a {@link Mono} containing modified HTML ready for browser rendering
     * @throws ResponseStatusException with {@code 400 Bad Request} for unknown roles and
     *         {@code 502 Bad Gateway} when loading {@code /graphiql} fails
     */
    @GetMapping(value = "/proxy/graphiql", produces = MediaType.TEXT_HTML_VALUE)
    public Mono<String> proxyGraphiQL(@RequestParam String role, ServerHttpRequest request) {
        String graphiqlUri = UriComponentsBuilder.fromUri(request.getURI())
            .replacePath("/graphiql")
            .replaceQuery(null)
            .build(true)
            .toUriString();
        return proxyGraphiQL(role, graphiqlUri);
    }


    /**
     * Loads GraphiQL HTML and injects role-based authentication headers.
     *
     * @param role credential profile key such as {@code admin}, {@code writer}, or {@code reader}
     * @param graphiqlUri URI used to fetch GraphiQL HTML
     * @return a {@link Mono} containing modified HTML ready for browser rendering
      * @throws ResponseStatusException with {@code 400 Bad Request} for unknown roles and
      *         {@code 502 Bad Gateway} when upstream GraphiQL cannot be loaded
     */
    private Mono<String> proxyGraphiQL(String role, @NonNull String graphiqlUri) {
        CredentialDto cred = credentials.get(role);

        if (cred == null) {
            log.error("Unknown role requested for GraphiQL proxy: '{}'", role);
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unknown role: " + role + ". Valid roles: " + credentials.keySet()));
        }

        String token = Base64.getEncoder().encodeToString(
            (cred.user() + ":" + cred.pass()).getBytes(StandardCharsets.UTF_8));
        String authHeader = "Basic " + token;

        log.debug("Proxying GraphiQL for role '{}' (user: {})", role, cred.user());

        return loadGraphiqlHtml(graphiqlUri)
            .map(this::alignGraphiqlDefaultPaths)
            .map(html -> injectAuthScript(html, authHeader))
            .doOnError(ex -> log.error("Failed to proxy GraphiQL for role '{}'", role, ex))
            .onErrorMap(ex -> !(ex instanceof ResponseStatusException),
                ex -> new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to load GraphiQL"));
    }

    /**
     * Rewrites GraphiQL default HTTP and WS paths to the configured GraphQL endpoint path.
     *
     * <p>
     * Spring GraphiQL defaults to {@code /graphql}. This proxy serves the same HTML under
     * a role-qualified URL where no {@code path} query parameter is typically provided,
     * so defaults must align with {@code dgs.graphql.path} (for this project, {@code /model}).
     *
     * @param html original GraphiQL HTML document
     * @return HTML with default fetch and websocket paths aligned to {@link #graphqlPath}
     */
    private String alignGraphiqlDefaultPaths(String html) {
        String normalizedPath = graphqlPath == null || graphqlPath.isBlank() ? "/graphql" : graphqlPath;
        String updatedHtml = html.replace("params.get(\"path\") || \"/graphql\"",
            "params.get(\"path\") || \"" + normalizedPath + "\"");
        return updatedHtml.replace("params.get(\"wsPath\") || \"/graphql\"",
            "params.get(\"wsPath\") || \"" + normalizedPath + "\"");
    }

    /**
     * Loads GraphiQL HTML and validates that it is not empty.
     *
     * @param graphiqlUri URI used to fetch GraphiQL HTML
     * @return a {@link Mono} containing non-empty GraphiQL HTML
     * @throws ResponseStatusException when upstream responds with empty content
     */
    private Mono<String> loadGraphiqlHtml(@NonNull String graphiqlUri) {
        return fetchGraphiqlHtml(graphiqlUri)
            .flatMap(html -> html.isBlank()
                ? Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "GraphiQL returned an empty HTML response"))
                : Mono.just(html));
    }

    /**
     * Fetches GraphiQL HTML and follows redirects explicitly.
     *
     * @param graphiqlUri URI used to fetch GraphiQL HTML
     * @return a {@link Mono} containing GraphiQL HTML response content
      * @throws ResponseStatusException when redirect/error handling eventually maps to a gateway error
     */
    private Mono<String> fetchGraphiqlHtml(@NonNull String graphiqlUri) {
        return webClient.get()
            .uri(graphiqlUri)
            .exchangeToMono(response -> handleGraphiqlResponse(graphiqlUri, response.statusCode(),
                response.headers().asHttpHeaders().getLocation(),
                response.bodyToMono(String.class).defaultIfEmpty(""),
                response.createException().flatMap(Mono::error)));
    }

    /**
     * Handles upstream GraphiQL HTTP responses and redirect traversal.
     *
     * @param requestUri URI used for the current request
     * @param statusCode HTTP status code returned by upstream
     * @param redirectLocation redirect location header if present
     * @param bodyMono response body publisher for successful responses
     * @param errorMono error publisher for non-success responses
     * @return a {@link Mono} containing GraphiQL HTML or an error
     */
    private Mono<String> handleGraphiqlResponse(
        String requestUri,
        HttpStatusCode statusCode,
        URI redirectLocation,
        Mono<String> bodyMono,
        Mono<String> errorMono) {
        if (statusCode.is2xxSuccessful()) {
            return bodyMono;
        }
        if (statusCode.is3xxRedirection() && redirectLocation != null) {
            URI resolved = URI.create(requestUri).resolve(redirectLocation);
            return fetchGraphiqlHtml(Objects.requireNonNull(resolved.toString()));
        }
        return errorMono;
    }

    /**
     * Injects a script that patches {@code window.fetch} for GraphQL requests.
     *
     * @param html original GraphiQL HTML document
     * @param authHeader precomputed {@code Authorization} header value
     * @return HTML with the script inserted before {@code </head>} or appended when absent
     */
    private String injectAuthScript(String html, String authHeader) {
        String script = """
            <script>
            (function () {
                var _fetch = window.fetch;
                window.fetch = function (url, opts) {
                    if (typeof url === 'string' && url.includes('%s')) {
                        opts = opts || {};
                        opts.headers = Object.assign({}, opts.headers, { 'Authorization': '%s' });
                    }
                    return _fetch.call(this, url, opts);
                };
            })();
            </script>
            """.formatted(graphqlPath, authHeader);

        int insertAt = html.indexOf("</head>");
        if (insertAt >= 0) {
            return html.substring(0, insertAt) + script + html.substring(insertAt);
        }
        return html + script;
    }
}
