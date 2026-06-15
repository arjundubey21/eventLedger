# Event Ledger

A two-service system that ingests financial transaction events and maintains account balances. It is built to tolerate the realities of upstream delivery: **duplicate** events and events that arrive **out of chronological order**, and it **degrades gracefully** when the internal service is unavailable.

Built with **Java 21** and **Spring Boot 4.1.0** (Maven multi-module).

---

## Architecture

```
                          ┌────────────────────────┐
 Browser / Client ─────▶  │   Event Gateway API    │   :8080  (public-facing)
                          │   - validation         │
                          │   - idempotency        │
                          │   - event store (H2)   │
                          │   - resiliency         │
                          └───────────┬────────────┘
                                      │ REST (sync) + W3C traceparent
                                      ▼
                          ┌────────────────────────┐
                          │    Account Service     │   :8081  (internal)
                          │   - applies tx (H2)    │
                          │   - balances           │
                          └────────────────────────┘
```

**Event Gateway (public).** The entry point. It validates input, enforces idempotency, durably stores every event in its own H2 database, and calls the Account Service to apply the transaction. All read endpoints are served from its local store, so they keep working even if the Account Service is down.

**Account Service (internal).** Owns account state. It applies transactions idempotently and computes balances. It is only called by the Gateway.

The two services are **independent processes with separate in-memory H2 databases** — they share no database and no in-process state. The contract between them is a small REST API (see below).

### Project layout

```
event-ledger/
├── pom.xml                      # parent (dependency & version management)
├── docker-compose.yml
├── account-service/             # internal service
│   ├── Dockerfile
│   └── src/main/java/com/eventledger/account/...
└── event-gateway/               # public service
    ├── Dockerfile
    └── src/main/java/com/eventledger/gateway/...
```

---

## API

### Event Gateway (`:8080`)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/events` | Submit a transaction event |
| `GET` | `/events/{id}` | Retrieve a single event |
| `GET` | `/events?account={accountId}` | List an account's events, ordered by event timestamp |
| `GET` | `/accounts/{accountId}/balance` | Balance (proxied to the Account Service) |
| `GET` | `/health` | Health check |

### Account Service (`:8081`, internal)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/accounts/{accountId}/transactions` | Apply a transaction |
| `GET` | `/accounts/{accountId}/balance` | Current balance |
| `GET` | `/accounts/{accountId}` | Details + chronological history |
| `GET` | `/health` | Health check |

### Example

```bash
curl -X POST http://localhost:8080/events \
  -H 'Content-Type: application/json' \
  -d '{
        "eventId": "evt-001",
        "accountId": "acct-123",
        "type": "CREDIT",
        "amount": 150.00,
        "currency": "USD",
        "eventTimestamp": "2026-05-15T14:02:11Z",
        "metadata": { "source": "mainframe-batch", "batchId": "B-9042" }
      }'

curl http://localhost:8080/events/evt-001
curl "http://localhost:8080/events?account=acct-123"
curl http://localhost:8080/accounts/acct-123/balance
```

Response codes for `POST /events`: **201** for a new event, **200** for an idempotent duplicate, **400** for validation errors, **503** when the Account Service is unavailable.

---

## Core behaviour

**Idempotency.** `eventId` is the primary key in both services. Re-submitting the same `eventId` returns the original event and never double-counts the balance. The Gateway returns `200` (instead of `201`) on a duplicate, and the Account Service short-circuits the replay.

**Out-of-order tolerance.** Arrival order is irrelevant. Event listings and account history are always ordered by `eventTimestamp` (a SQL `ORDER BY`), and the balance is computed from an aggregate (`SUM(CREDIT) − SUM(DEBIT)`), which is independent of insertion order.

**Validation.** Missing required fields, non-positive amounts, and unknown `type` values are rejected with `400` and a descriptive message.

---

## Distributed tracing

Tracing is a dependency-light implementation built around a servlet filter and the SLF4J MDC (see `DESIGN.md` for why this was chosen over the full OpenTelemetry stack on Spring Boot 4).

- A trace ID is generated at the Gateway for each incoming request (a `TraceFilter`), or reused if an `X-Trace-Id` header is already present.
- It is propagated to the Account Service via the **`X-Trace-Id`** header, added by a `RestClient` request interceptor.
- Both services store the trace ID in the MDC, so it appears in their structured JSON logs, and echo it on the response.
- A single client request therefore produces one correlated trace ID across both services and their logs.

`GatewayIntegrationTest#traceContextIsPropagatedToAccountService` asserts that the `X-Trace-Id` header reaches the downstream service.

---

## Observability

**Structured logging.** Both services log JSON (Elastic Common Schema) to the console, including timestamp, level, service name, and the trace ID (from the MDC) (`logging.structured.format.console: ecs`).

**Health.** `GET /health` on each service returns overall status plus database connectivity. The Gateway's health reflects only its own database, so it stays `UP` when the Account Service is down (Account reachability is reported as an informational component). Spring Actuator's `/actuator/health` is also available.

**Metrics.** A custom counter `gateway.events.processed` (tags: `type`, `outcome` = `created` / `duplicate` / `degraded`) and `account.transactions.applied` are exposed, alongside Resilience4j circuit-breaker / retry gauges, at `/actuator/metrics` and `/actuator/prometheus`.

---

## Resiliency

**Chosen pattern: Timeout + Retry with exponential backoff + Circuit breaker**, applied to the Gateway → Account Service call (Resilience4j, wired programmatically in `ResilienceConfig`).

How the layers combine on each call:

1. **Timeout** — the `RestClient` has connect (1s) and read (2s) timeouts, so a slow Account Service can never hang Gateway threads indefinitely.
2. **Retry with backoff** — transient failures (timeouts, `5xx`) are retried up to 3 attempts with exponential backoff (200ms × 2). `4xx` responses and open-circuit rejections are never retried.
3. **Circuit breaker** — counts only infrastructure failures. After the failure rate crosses 50% over a sliding window, it opens for 5s and calls fail fast with `503` instead of piling up; it then probes in half-open state before closing.

**Why this combination?** A retry alone helps with one-off blips but amplifies load during a real outage; a circuit breaker alone reacts only after failures accumulate. Together they cover both: retries absorb transient errors, the timeout bounds latency, and the breaker protects the Gateway (and the struggling downstream) once failures become systemic — turning a cascading failure into a fast, clear `503`.

### Graceful degradation

| Scenario | Behaviour when Account Service is down |
|---|---|
| `POST /events` | Event is **stored locally first**, then the downstream call fails → returns **`503`**. The event is durably recorded as `PENDING`; a later re-submit finishes applying it. |
| `GET /events/{id}` and `GET /events?account=` | **Still work** — served entirely from the Gateway's local store. |
| `GET /accounts/{id}/balance` | Returns **`503`** with a clear message (balances are owned by the Account Service). |

---

## Running

### Prerequisites
- **Docker + Docker Compose** (recommended), or
- **JDK 21** and **Maven 3.9+** for manual runs.

### Option A — Docker Compose (recommended)

```bash
docker compose up --build
```

This builds both images and starts the Account Service (`:8081`) and Event Gateway (`:8080`). The Gateway waits for the Account Service to become healthy and is configured to reach it at `http://account-service:8081`.

### Option B — Run manually (two terminals)

```bash
# Terminal 1 — Account Service (port 8081)
mvn -pl account-service spring-boot:run

# Terminal 2 — Event Gateway (port 8080)
mvn -pl event-gateway spring-boot:run
```

The Gateway defaults to `http://localhost:8081` for the Account Service; override with `ACCOUNT_SERVICE_BASE_URL` if needed.

---

## Tests

```bash
mvn test
```

Coverage:

- **Core** — idempotency, out-of-order ordering, balance correctness, validation (`AccountIntegrationTest`, `EventGatewayServiceTest`, `EventControllerWebTest`).
- **Resiliency** — simulated Account Service failures verifying the circuit breaker opens and that retries recover from transient errors (`AccountServiceClientResilienceTest`).
- **Trace propagation** — asserts the `X-Trace-Id` header flows Gateway → Account Service (`GatewayIntegrationTest`).
- **Integration** — full `POST /events` → Account Service flow, plus graceful-degradation reads when the downstream is down (`GatewayIntegrationTest`).

---

## Notes & trade-offs

- **In-memory H2** means state resets on restart, which suits the exercise. The `PENDING` event design means a real deployment could add a background reconciler to retry pending events automatically rather than waiting for a client re-submit.
- Both services use their **own** database; there is no shared persistence or in-process coupling.
- Tracing is intentionally dependency-light (trace IDs generated, propagated via `X-Trace-Id`, and logged) rather than a full OpenTelemetry stack — see `DESIGN.md` for the rationale. OTLP export could be added later via `spring-boot-starter-opentelemetry`.
