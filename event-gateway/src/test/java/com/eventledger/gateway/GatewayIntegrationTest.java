package com.eventledger.gateway;

import com.eventledger.gateway.repository.EventRecordRepository;
import com.eventledger.gateway.web.TraceFilter;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full Gateway -> Account Service integration. A lightweight JDK {@link HttpServer} stands in for
 * the Account Service so we can drive the real RestClient + resiliency + tracing path, capture the
 * outgoing trace header, and simulate downstream outages.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GatewayIntegrationTest {

    private static HttpServer stub;
    private static volatile boolean failMode = false;
    private static volatile String lastTraceId;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CircuitBreaker accountServiceCircuitBreaker;

    @Autowired
    private EventRecordRepository repository;

    @DynamicPropertySource
    static void accountServiceProps(DynamicPropertyRegistry registry) throws IOException {
        stub = HttpServer.create(new InetSocketAddress(0), 0);
        stub.createContext("/accounts", exchange -> {
            lastTraceId = exchange.getRequestHeaders().getFirst(TraceFilter.TRACE_HEADER);
            exchange.getRequestBody().readAllBytes(); // drain
            String path = exchange.getRequestURI().getPath();
            if (failMode) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            if (path.endsWith("/balance")) {
                byte[] body = ("{\"accountId\":\"acct-int\",\"balance\":150.00,"
                        + "\"totalCredits\":150.00,\"totalDebits\":0,\"transactionCount\":1}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            } else {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            }
        });
        stub.start();
        int port = stub.getAddress().getPort();
        registry.add("account-service.base-url", () -> "http://localhost:" + port);
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    @BeforeEach
    void reset() {
        failMode = false;
        lastTraceId = null;
        accountServiceCircuitBreaker.reset();
        repository.deleteAll(); // isolate each test (no rollback under @SpringBootTest)
    }

    @AfterEach
    void cleanup() {
        failMode = false;
        accountServiceCircuitBreaker.reset();
    }

    private String event(String id, String account) {
        return """
                {"eventId":"%s","accountId":"%s","type":"CREDIT","amount":150.00,"currency":"USD",
                 "eventTimestamp":"2026-05-15T14:02:11Z","metadata":{"source":"test"}}
                """.formatted(id, account);
    }

    @Test
    void fullFlowSubmitThenReadThenBalance() throws Exception {
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(event("evt-int-1", "acct-int")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("APPLIED")));

        mockMvc.perform(get("/events/evt-int-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId", is("evt-int-1")));

        mockMvc.perform(get("/events").param("account", "acct-int"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId", is("evt-int-1")));

        // Balance is proxied from the (stub) Account Service.
        mockMvc.perform(get("/accounts/acct-int/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is(150.00)));
    }

    @Test
    void traceContextIsPropagatedToAccountService() throws Exception {
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(event("evt-trace", "acct-int")))
                .andExpect(status().isCreated());

        // The Gateway must have generated a trace id and forwarded it to the Account Service via
        // the X-Trace-Id header — proving a single request is traceable across both services.
        assertThat(lastTraceId).isNotNull();
        assertThat(lastTraceId).matches("[0-9a-f]{32}");
    }

    @Test
    void gracefulDegradationWhenAccountServiceDown() throws Exception {
        failMode = true;

        // POST -> 503 (not 500, not a hang).
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(event("evt-degraded", "acct-int")))
                .andExpect(status().isServiceUnavailable());

        // Reads of local event data still work even though the downstream service is down.
        mockMvc.perform(get("/events/evt-degraded"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PENDING")));

        mockMvc.perform(get("/events").param("account", "acct-int"))
                .andExpect(status().isOk());

        // Balance depends on the Account Service -> 503.
        mockMvc.perform(get("/accounts/acct-int/balance"))
                .andExpect(status().isServiceUnavailable());
    }
}
