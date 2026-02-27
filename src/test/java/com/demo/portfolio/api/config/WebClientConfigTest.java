package com.demo.portfolio.api.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.server.WebServer;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("null")
class WebClientConfigTest {

    private WebClientConfig config;

    @BeforeEach
    void setUp() {
        config = new WebClientConfig();
    }

    @Test
    void testOnApplicationEvent() {
        // arrange - create config and a fake server that returns a known port
        WebServer server = mock(WebServer.class);
        when(server.getPort()).thenReturn(12345);
        WebServerInitializedEvent event = mock(WebServerInitializedEvent.class);
        when(event.getWebServer()).thenReturn(server); 

        // act
        config.onApplicationEvent(event);

        // assert - the private field should have been set
        Integer portValue = (Integer) ReflectionTestUtils.getField(config, "port");
        assertEquals(12345, portValue);
    }

    @Test
    void testWebClient() {
        // arrange - set port before building client
        ReflectionTestUtils.setField(config, "port", 4567);

        // act
        WebClient client = config.webClient();

        // assert - perform a request with a capturing ExchangeFunction to verify resolved URL uses baseUrl
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ExchangeFunction captureExchange = req -> {
            captured.set(req);
            Mono<ClientResponse> mono = Mono.just(ClientResponse.create(HttpStatus.OK).build());
            assertNotNull(mono);
            return mono;
        };

        WebClient mutated = client.mutate().exchangeFunction(captureExchange).build();
        // trigger the exchange to allow the ExchangeFunction to capture the request
        mutated.get().uri("/test-path").exchangeToMono(Mono::just).block();

        ClientRequest req = captured.get();
        Objects.requireNonNull(req);
        assertEquals("http://localhost:4567/test-path", req.url().toString());
    }
}
