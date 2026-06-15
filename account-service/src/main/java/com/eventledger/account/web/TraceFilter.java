package com.eventledger.account.web;

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
 * Distributed-tracing entry point for the Account Service.
 *
 * <p>Reads the {@code X-Trace-Id} header propagated by the Event Gateway (or generates a new id if
 * absent), stores it in the SLF4J {@link MDC} so it is emitted in the structured JSON logs, and
 * echoes it back on the response. A single client request therefore shares one trace id across both
 * services and their logs.
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
