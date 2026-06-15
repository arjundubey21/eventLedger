package com.eventledger.gateway.config;

import com.eventledger.gateway.web.TraceFilter;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    /**
     * RestClient pointed at the Account Service.
     *
     * <p>A request interceptor forwards the current trace id (held in the MDC by {@code TraceFilter})
     * to the Account Service via the {@code X-Trace-Id} header, so a single request is traceable
     * across both services. Connect/read timeouts implement the "timeout" half of the resiliency
     * strategy so a slow Account Service can't hang Gateway threads.
     */
    @Bean
    public RestClient accountServiceRestClient(
            @Value("${account-service.base-url:http://localhost:8081}") String baseUrl,
            @Value("${account-service.connect-timeout-millis:1000}") long connectTimeoutMillis,
            @Value("${account-service.read-timeout-millis:2000}") long readTimeoutMillis) {

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMillis));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    String traceId = MDC.get(TraceFilter.MDC_KEY);
                    if (traceId != null) {
                        request.getHeaders().add(TraceFilter.TRACE_HEADER, traceId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
