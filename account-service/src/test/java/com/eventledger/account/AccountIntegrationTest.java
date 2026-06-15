package com.eventledger.account;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AccountIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private String tx(String eventId, String type, String amount, String ts) {
        return """
                {"eventId":"%s","type":"%s","amount":%s,"currency":"USD","eventTimestamp":"%s"}
                """.formatted(eventId, type, amount, ts);
    }

    @Test
    void appliesTransactionAndReturns201() throws Exception {
        mockMvc.perform(post("/accounts/acct-100/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tx("evt-a1", "CREDIT", "150.00", "2026-05-15T14:02:11Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId", is("evt-a1")))
                .andExpect(jsonPath("$.accountId", is("acct-100")));
    }

    @Test
    void duplicateEventIdIsIdempotentAndReturns200() throws Exception {
        String body = tx("evt-dup", "CREDIT", "100.00", "2026-05-15T10:00:00Z");

        mockMvc.perform(post("/accounts/acct-dup/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        // Same eventId again -> 200, no duplicate, balance unchanged.
        mockMvc.perform(post("/accounts/acct-dup/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mockMvc.perform(get("/accounts/acct-dup/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is(100.00)))
                .andExpect(jsonPath("$.transactionCount", is(1)));
    }

    @Test
    void balanceIsCreditsMinusDebits() throws Exception {
        mockMvc.perform(post("/accounts/acct-bal/transactions").contentType(MediaType.APPLICATION_JSON)
                .content(tx("evt-b1", "CREDIT", "200.00", "2026-05-15T10:00:00Z"))).andExpect(status().isCreated());
        mockMvc.perform(post("/accounts/acct-bal/transactions").contentType(MediaType.APPLICATION_JSON)
                .content(tx("evt-b2", "DEBIT", "75.50", "2026-05-15T11:00:00Z"))).andExpect(status().isCreated());

        mockMvc.perform(get("/accounts/acct-bal/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is(124.50)));
    }

    @Test
    void balanceCorrectAndHistoryOrderedDespiteOutOfOrderArrival() throws Exception {
        // Arrives later in time but with an EARLIER timestamp than the next one.
        mockMvc.perform(post("/accounts/acct-ooo/transactions").contentType(MediaType.APPLICATION_JSON)
                .content(tx("evt-late", "DEBIT", "30.00", "2026-05-15T09:00:00Z"))).andExpect(status().isCreated());
        mockMvc.perform(post("/accounts/acct-ooo/transactions").contentType(MediaType.APPLICATION_JSON)
                .content(tx("evt-early", "CREDIT", "100.00", "2026-05-15T08:00:00Z"))).andExpect(status().isCreated());

        // Balance is order-independent: 100 - 30 = 70.
        mockMvc.perform(get("/accounts/acct-ooo/balance"))
                .andExpect(jsonPath("$.balance", is(70.00)));

        // History is ordered by eventTimestamp ascending, not arrival order.
        mockMvc.perform(get("/accounts/acct-ooo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions[0].eventId", is("evt-early")))
                .andExpect(jsonPath("$.transactions[1].eventId", is("evt-late")));
    }

    @Test
    void rejectsNegativeAmountWith400() throws Exception {
        mockMvc.perform(post("/accounts/acct-v/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content(tx("evt-neg", "CREDIT", "-5.00", "2026-05-15T10:00:00Z")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownTypeWith400() throws Exception {
        mockMvc.perform(post("/accounts/acct-v/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content(tx("evt-bad", "TRANSFER", "5.00", "2026-05-15T10:00:00Z")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingFieldWith400() throws Exception {
        mockMvc.perform(post("/accounts/acct-v/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"evt-missing","amount":5.00,"currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsSameEventIdWithDifferentPayloadWith409() throws Exception {
        mockMvc.perform(post("/accounts/acct-c/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content(tx("evt-c1", "CREDIT", "100.00", "2026-05-15T10:00:00Z")))
                .andExpect(status().isCreated());
        // Same eventId, different amount -> conflict.
        mockMvc.perform(post("/accounts/acct-c/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content(tx("evt-c1", "CREDIT", "200.00", "2026-05-15T10:00:00Z")))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsCurrencyMismatchForAccountWith409() throws Exception {
        mockMvc.perform(post("/accounts/acct-cur/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content(tx("evt-usd", "CREDIT", "100.00", "2026-05-15T10:00:00Z")))
                .andExpect(status().isCreated());
        // Different currency on the same account -> conflict (accounts are single-currency).
        mockMvc.perform(post("/accounts/acct-cur/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"evt-eur","type":"CREDIT","amount":50.00,"currency":"EUR","eventTimestamp":"2026-05-15T11:00:00Z"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void echoesTraceIdHeaderForCrossServiceCorrelation() throws Exception {
        mockMvc.perform(post("/accounts/acct-tr/transactions").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Trace-Id", "trace-abc-123")
                        .content(tx("evt-tr", "CREDIT", "10.00", "2026-05-15T10:00:00Z")))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Trace-Id", "trace-abc-123"));
    }
}
