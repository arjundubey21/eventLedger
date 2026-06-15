package com.eventledger.gateway.web;

import com.eventledger.gateway.client.AccountServiceUnavailableException;
import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.dto.ProcessResult;
import com.eventledger.gateway.model.EventStatus;
import com.eventledger.gateway.service.EventGatewayService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventController.class)
class EventControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventGatewayService service;

    private ProcessResult result(boolean created) {
        EventResponse e = new EventResponse("evt-1", "acct-1", null, new BigDecimal("10.00"),
                "USD", Instant.parse("2026-05-15T10:00:00Z"), Map.of(), EventStatus.APPLIED, Instant.now());
        return new ProcessResult(e, created);
    }

    private String body(String type, String amount) {
        return """
                {"eventId":"evt-1","accountId":"acct-1","type":"%s","amount":%s,"currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}
                """.formatted(type, amount);
    }

    @Test
    void newEventReturns201() throws Exception {
        when(service.process(any(EventRequest.class))).thenReturn(result(true));
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body("CREDIT", "10.00")))
                .andExpect(status().isCreated());
    }

    @Test
    void duplicateEventReturns200() throws Exception {
        when(service.process(any(EventRequest.class))).thenReturn(result(false));
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body("CREDIT", "10.00")))
                .andExpect(status().isOk());
    }

    @Test
    void accountServiceUnavailableReturns503() throws Exception {
        when(service.process(any(EventRequest.class)))
                .thenThrow(new AccountServiceUnavailableException("Account Service is unreachable"));
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body("CREDIT", "10.00")))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void missingAccountIdReturns400() throws Exception {
        String json = """
                {"eventId":"evt-1","type":"CREDIT","amount":10.00,"currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}
                """;
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void negativeAmountReturns400() throws Exception {
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body("CREDIT", "-1.00")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownTypeReturns400() throws Exception {
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body("TRANSFER", "10.00")))
                .andExpect(status().isBadRequest());
    }
}
