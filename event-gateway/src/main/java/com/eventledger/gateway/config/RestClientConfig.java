package com.eventledger.gateway.config;

import com.eventledger.gateway.web.TraceFilter;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    /**
     * RestClient pointed at the Account Service.
     *
     * <p>Backed by a pooled JDK {@link HttpClient} (keep-alive / connection reuse) so it scales under
     * load instead of opening a fresh connection per call. A request interceptor forwards the current
     * trace id (held in the MDC by {@code TraceFilter}) via the {@code X-Trace-Id} header, so a single
     * request is traceable across both services. Connect/read timeouts implement the "timeout" half of
     * the resiliency strategy so a slow Account Service can't hang Gateway threads.
     */
    @Bean
    public RestClient accountServiceRestClient(
            @Value("${account-service.base-url:http://localhost:8081}") String baseUrl,
            @Value("${account-service.connect-timeout-millis:1000}") long connectTimeoutMillis,
            @Value("${account-service.read-timeout-millis:2000}") long readTimeoutMillis) {

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
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
