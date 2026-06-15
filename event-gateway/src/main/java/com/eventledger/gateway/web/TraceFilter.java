package com.eventledger.gateway.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Distributed-tracing entry point for the public Event Gateway.
 *
 * <p>Generates a trace id for each incoming request (or reuses an inbound {@code X-Trace-Id}),
 * stores it in the SLF4J {@link MDC} so it appears in the structured JSON logs, and echoes it on the
 * response. The id is forwarded to the Account Service by the RestClient interceptor (see
 * {@code RestClientConfig}), giving a single traceable path across both services.
 */
@Component
@Order(1)
public class TraceFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = newTraceId();
        }
        MDC.put(MDC_KEY, traceId);
        response.setHeader(TRACE_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
