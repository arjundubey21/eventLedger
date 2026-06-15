package com.eventledger.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    /**
     * RestClient pointed at the Account Service.
     *
     * <p>Built from the auto-configured {@link RestClient.Builder} so that Micrometer's observation
     * instrumentation is applied — this is what propagates the trace context to the downstream
     * service via the W3C {@code traceparent} header. Connect/read timeouts implement the "timeout"
     * half of the resiliency strategy so a slow Account Service can't hang Gateway threads.
     */
    @Bean
    public RestClient accountServiceRestClient(
            RestClient.Builder builder,
            @Value("${account-service.base-url:http://localhost:8081}") String baseUrl,
            @Value("${account-service.connect-timeout-millis:1000}") long connectTimeoutMillis,
            @Value("${account-service.read-timeout-millis:2000}") long readTimeoutMillis) {

        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .withReadTimeout(Duration.ofMillis(readTimeoutMillis));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings);

        return builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
