package com.demo.portfolio.api.config;

import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.reactive.function.client.WebClient;

import lombok.extern.slf4j.Slf4j;

/**
 * Configures a local {@link WebClient} used for internal server-to-server calls.
 *
 * <p>The base URL depends on the runtime HTTP port assigned to the current Spring Boot
 * application instance. The port is captured from {@link WebServerInitializedEvent}.
 */
@Configuration
@Slf4j
public class WebClientConfig implements ApplicationListener<WebServerInitializedEvent> {

    private int port;

    /**
     * Captures the web server port once the embedded server is initialized.
     *
     * @param event the server initialization event containing the active {@link org.springframework.boot.web.server.WebServer}
     */
    @Override
    public void onApplicationEvent(@NonNull WebServerInitializedEvent event) {
        this.port = event.getWebServer().getPort();
    }

    /**
     * Creates a {@link WebClient} bound to the local application base URL.
     *
     * @return a WebClient configured with {@code http://localhost:<port>} as base URL
     */
    @Bean
    public WebClient webClient() {
        return WebClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }
}
