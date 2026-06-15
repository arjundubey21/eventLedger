package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.client.AccountServiceUnavailableException;
import com.eventledger.gateway.client.AccountTransactionRequest;
import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.dto.ProcessResult;
import com.eventledger.gateway.model.EventStatus;
import com.eventledger.gateway.model.TransactionType;
import com.eventledger.gateway.repository.EventRecordRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DataJpaTest
class EventGatewayServiceTest {

    @Autowired
    private EventRecordRepository repository;

    private AccountServiceClient client;
    private EventGatewayService service;

    @BeforeEach
    void setUp() {
        client = mock(AccountServiceClient.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC);
        service = new EventGatewayService(repository, client, new SimpleMeterRegistry(), clock);
    }

    private EventRequest event(String id, String account, TransactionType type, String amount, String ts) {
        return new EventRequest(id, account, type, new BigDecimal(amount), "USD", Instant.parse(ts),
                Map.of("source", "test"));
    }

    @Test
    void newEventIsAppliedAndMarkedCreated() {
        doNothing().when(client).applyTransaction(anyString(), any(AccountTransactionRequest.class));

        ProcessResult result = service.process(
                event("evt-1", "acct-1", TransactionType.CREDIT, "150.00", "2026-05-15T14:02:11Z"));

        assertThat(result.created()).isTrue();
        assertThat(result.event().status()).isEqualTo(EventStatus.APPLIED);
        verify(client, times(1)).applyTransaction(anyString(), any());
    }

    @Test
    void duplicateEventIsIdempotentAndDoesNotCallDownstreamAgain() {
        doNothing().when(client).applyTransaction(anyString(), any());
        EventRequest req = event("evt-dup", "acct-1", TransactionType.CREDIT, "100.00", "2026-05-15T10:00:00Z");

        service.process(req);
        ProcessResult second = service.process(req);

        assertThat(second.created()).isFalse();
        // Downstream applied exactly once despite two submissions.
        verify(client, times(1)).applyTransaction(anyString(), any());
    }

    @Test
    void eventsAreListedInChronologicalOrderRegardlessOfArrivalOrder() {
        doNothing().when(client).applyTransaction(anyString(), any());
        // Submit out of chronological order.
        service.process(event("evt-late", "acct-2", TransactionType.DEBIT, "30.00", "2026-05-15T12:00:00Z"));
        service.process(event("evt-early", "acct-2", TransactionType.CREDIT, "100.00", "2026-05-15T08:00:00Z"));

        List<EventResponse> events = service.getEventsForAccount("acct-2");

        assertThat(events).extracting(EventResponse::eventId)
                .containsExactly("evt-early", "evt-late");
    }

    @Test
    void whenAccountServiceUnavailableEventIsStoredPendingAndExceptionPropagates() {
        doThrow(new AccountServiceUnavailableException("down"))
                .when(client).applyTransaction(anyString(), any());

        assertThatThrownBy(() -> service.process(
                event("evt-degraded", "acct-3", TransactionType.CREDIT, "50.00", "2026-05-15T10:00:00Z")))
                .isInstanceOf(AccountServiceUnavailableException.class);

        // Graceful degradation: the event is still durably stored and queryable locally.
        EventResponse stored = service.getEvent("evt-degraded").orElseThrow();
        assertThat(stored.status()).isEqualTo(EventStatus.PENDING);
    }

    @Test
    void resubmittingPendingEventAppliesItWhenServiceRecovers() {
        EventRequest req = event("evt-recover", "acct-4", TransactionType.CREDIT, "25.00", "2026-05-15T10:00:00Z");

        doThrow(new AccountServiceUnavailableException("down")).when(client).applyTransaction(anyString(), any());
        assertThatThrownBy(() -> service.process(req)).isInstanceOf(AccountServiceUnavailableException.class);
        assertThat(service.getEvent("evt-recover").orElseThrow().status()).isEqualTo(EventStatus.PENDING);

        // Service recovers; resubmission finishes applying the pending event.
        doNothing().when(client).applyTransaction(anyString(), any());
        ProcessResult result = service.process(req);

        assertThat(result.event().status()).isEqualTo(EventStatus.APPLIED);
    }
}
