package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountBalance;
import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.client.AccountServiceUnavailableException;
import com.eventledger.gateway.client.AccountTransactionRequest;
import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.dto.ProcessResult;
import com.eventledger.gateway.model.EventRecord;
import com.eventledger.gateway.model.EventStatus;
import com.eventledger.gateway.repository.EventRecordRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

@Service
public class EventGatewayService {

    private static final Logger log = LoggerFactory.getLogger(EventGatewayService.class);

    private final EventRecordRepository repository;
    private final AccountServiceClient accountServiceClient;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public EventGatewayService(EventRecordRepository repository,
                               AccountServiceClient accountServiceClient,
                               MeterRegistry meterRegistry,
                               Clock clock) {
        this.repository = repository;
        this.accountServiceClient = accountServiceClient;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * Processes an incoming event.
     *
     * <p>Idempotency: keyed on {@code eventId}. A replay of an already-applied event returns the
     * stored event without touching the Account Service or the balance.
     *
     * <p>Graceful degradation: the event is persisted locally <i>before</i> the downstream call, so
     * if the Account Service is unavailable the event is still durably recorded (and queryable) and
     * the caller gets a 503. A later retry of the same event finishes applying the pending record.
     */
    public ProcessResult process(EventRequest request) {
        Optional<EventRecord> existing = repository.findById(request.eventId());
        if (existing.isPresent()) {
            return handleExisting(existing.get(), request);
        }

        // New event: persist as PENDING first so it survives a downstream outage. The eventId
        // primary key makes this insert the single source of truth under concurrency.
        EventRecord record;
        try {
            record = repository.saveAndFlush(new EventRecord(
                    request.eventId(), request.accountId(), request.type(), request.amount(),
                    request.currency(), request.eventTimestamp(), request.metadata(),
                    EventStatus.PENDING, clock.instant()));
        } catch (DataIntegrityViolationException raced) {
            // A concurrent request created the same eventId first; resolve to that record.
            EventRecord winner = repository.findById(request.eventId()).orElseThrow(() -> raced);
            return handleExisting(winner, request);
        }
        log.info("Event received: eventId={} accountId={} type={} amount={}",
                record.getEventId(), record.getAccountId(), record.getType(), record.getAmount());

        applyDownstream(record);
        count(request, "created");
        return new ProcessResult(EventResponse.from(record), true);
    }

    /** Resolves a submission whose eventId already exists: replay (idempotent) or conflict. */
    private ProcessResult handleExisting(EventRecord record, EventRequest request) {
        assertSamePayload(record, request);
        if (record.getStatus() == EventStatus.APPLIED) {
            log.info("Duplicate event ignored: eventId={} accountId={}",
                    record.getEventId(), record.getAccountId());
            count(request, "duplicate");
            return new ProcessResult(EventResponse.from(record), false);
        }
        // Previously received but not yet applied (downstream was down). Try again now.
        log.info("Re-processing pending event: eventId={}", record.getEventId());
        applyDownstream(record);
        return new ProcessResult(EventResponse.from(record), false);
    }

    private void assertSamePayload(EventRecord record, EventRequest request) {
        boolean same = record.getAccountId().equals(request.accountId())
                && record.getType() == request.type()
                && record.getAmount().compareTo(request.amount()) == 0
                && record.getCurrency().equals(request.currency())
                && record.getEventTimestamp().equals(request.eventTimestamp());
        if (!same) {
            throw new EventConflictException("eventId " + record.getEventId()
                    + " already exists with a different payload");
        }
    }

    /**
     * Calls the Account Service to apply the transaction and marks the event APPLIED on success.
     * If the service is unavailable, the resilient client throws {@link AccountServiceUnavailableException}
     * and the event stays PENDING for a later retry. Other errors (e.g. a downstream 4xx) propagate.
     */
    private void applyDownstream(EventRecord record) {
        try {
            accountServiceClient.applyTransaction(record.getAccountId(),
                    new AccountTransactionRequest(record.getEventId(), record.getType(),
                            record.getAmount(), record.getCurrency(), record.getEventTimestamp()));
            record.setStatus(EventStatus.APPLIED);
            repository.save(record);
            log.info("Event applied downstream: eventId={}", record.getEventId());
        } catch (AccountServiceUnavailableException e) {
            meterRegistry.counter("gateway.events.processed",
                    "type", record.getType().name(), "outcome", "degraded").increment();
            log.warn("Event stored but not applied (Account Service unavailable): eventId={}",
                    record.getEventId());
            throw e;
        }
    }

    public Optional<EventResponse> getEvent(String eventId) {
        return repository.findById(eventId).map(EventResponse::from);
    }

    public List<EventResponse> getEventsForAccount(String accountId, Pageable pageable) {
        return repository.findByAccountIdOrderByEventTimestampAsc(accountId, pageable).stream()
                .map(EventResponse::from)
                .toList();
    }

    /** Proxies a balance query to the Account Service (resiliency applies). */
    public AccountBalance getBalance(String accountId) {
        return accountServiceClient.getBalance(accountId);
    }

    private void count(EventRequest request, String outcome) {
        meterRegistry.counter("gateway.events.processed",
                "type", request.type().name(), "outcome", outcome).increment();
    }
}
