# Design Decisions & Trade-offs

This document explains *why* the Event Ledger is built the way it is. The code comments cover the
*how*; this covers the reasoning a reviewer would otherwise have to infer.

## Service boundaries

The Gateway owns *ingestion and the event record*; the Account Service owns *account state and
balances*. Each has its own H2 database and they communicate only over REST. This keeps the public
surface (validation, idempotency, durability of the raw event) separate from the financial state
machine, and lets either service be scaled, deployed, or failed independently. The cost is a network
hop and the need to reason about partial failure — which the resiliency and graceful-degradation
work below is all about.

## Idempotency

`eventId` is the **primary key** in both services rather than a separate dedupe table or an
application-level "have I seen this?" check. Using the natural key as the PK means the database
enforces exactly-once storage for us, with no race window. A replay returns the stored event with
`200` (vs `201` for a new one) so clients can tell the difference. The Account Service is *also*
idempotent on `eventId`, so even if the Gateway retries a downstream call, the balance can never be
double-counted.

## Out-of-order arrival

Two independent guarantees make arrival order irrelevant:

- **Ordering** is applied at read time (`ORDER BY eventTimestamp`), not at write time, so a
  late-arriving older event simply sorts into place.
- **Balance** is computed as an aggregate (`SUM(CREDIT) − SUM(DEBIT)`), which is commutative — the
  result is identical regardless of the order rows were inserted.

This avoids any need to buffer, reorder, or re-sequence events on ingest.

## Graceful degradation and the `PENDING` state

The Gateway persists an event **before** calling the Account Service, marking it `PENDING`. If the
downstream call then fails, the event is still durably recorded and queryable, and the client gets a
`503` (not a `500`, and not a hang). Reads (`GET /events/...`) keep working because they only touch
the Gateway's local data. A later re-submission of the same event finishes applying the `PENDING`
record. This was a deliberate choice: the alternative (refuse to store anything unless the whole
chain succeeds) would lose the durability and idempotency benefits and make retries harder.

*Next step in a real system:* a background reconciler that retries `PENDING` events automatically
instead of waiting for a client re-submit.

## Resiliency: timeout + retry-with-backoff + circuit breaker

Implemented on the Gateway → Account Service call with Resilience4j, wired programmatically so it's
independent of the Spring Boot starter version. The three layers compose:

1. **Timeout** bounds latency so a slow downstream can't tie up Gateway threads.
2. **Retry with exponential backoff** absorbs transient blips (timeouts, 5xx). It deliberately does
   *not* retry 4xx or open-circuit rejections.
3. **Circuit breaker** trips once failures are systemic, failing fast with a `503` instead of
   piling up — protecting both the Gateway and the already-struggling downstream.

Why all three rather than one: a retry alone amplifies load during a real outage; a breaker alone
reacts only after failures accumulate. Together they handle both transient and sustained failure.

## Distributed tracing

The requirement is to generate a trace id, propagate it across the Gateway → Account Service hop,
and log it in both services (OpenTelemetry preferred, not required). I chose a **dependency-light
implementation** over the full OTel stack:

- A servlet filter generates (or reuses) an `X-Trace-Id`, stores it in the SLF4J MDC so it appears
  in the structured JSON logs, and echoes it on the response.
- A `RestClient` interceptor forwards the id downstream, so one request is traceable across both
  services.

The full Micrometer/OpenTelemetry route was evaluated and rejected for this exercise: on Spring Boot
4 the OTel SDK auto-configuration moved into `spring-boot-starter-opentelemetry`, and it expects an
OTLP collector to export to — infrastructure that adds noise and setup cost without serving the
actual requirement. The chosen approach delivers the same observable outcome (a correlated trace id
across services and logs) with no external dependencies, and could be swapped for OTLP export later
by adding the starter.

## Observability

- **Structured logging:** JSON (Elastic Common Schema) on both services, with the trace id pulled
  from the MDC.
- **Health:** `GET /health` reports DB connectivity. The Gateway's health intentionally reflects
  only its own database, so it stays `UP` when the Account Service is down (local reads still work);
  downstream reachability is reported as a diagnostic, not as overall status.
- **Metrics:** custom counters (`gateway.events.processed`, `account.transactions.applied`) plus
  Resilience4j circuit-breaker/retry gauges, exposed at `/actuator/prometheus`.

## Spring Boot 4 / Jackson 3 notes

The project targets the latest Spring Boot (4.x), which brings Jackson 3 (`tools.jackson`,
unchecked exceptions) and modularized test slices. Decisions made for resilience to these changes:
the JSON converter uses the new `JsonMapper` builder; exception handlers detect bad input from the
message rather than depending on a Jackson-internal exception class; and the HTTP client sets
timeouts via plain Spring `SimpleClientHttpRequestFactory` instead of a Boot helper whose package
moved between versions.

## Testing strategy

Tests are layered to match the requirements: fast slice/unit tests for the web layer and resiliency
(`@WebMvcTest`, `MockRestServiceServer` to simulate downstream failures and prove the breaker
opens / retry recovers), and full-context `@SpringBootTest` integration tests that drive the real
RestClient against a lightweight in-process stub to verify the end-to-end flow, trace-id
propagation, and graceful degradation. Integration tests reset state per test for isolation, since
`@SpringBootTest` doesn't roll back like a JPA slice would.
