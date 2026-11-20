# Meridian Mutual — Payment Webhook Ingestion Platform

Backend engineering solution for **Meridian Mutual**, covering system architecture, API and event contracts, production incident PR review, and a runnable **Java 21 / Spring Boot 3** webhook ingestion microservice (`payments-intake`).

---

## What Was Built

Meridian Mutual processes policy premium payments from regional gateways (**Andina Pay** in Colombia and **Quetzal Pagos** in Mexico) and synchronizes policy status downstream with Salesforce CRM.

This repository delivers two integrated parts:

1. **Part A — Architecture, Contracts & Incident Review:**
   * End-to-end ingestion design addressing webhook bursts, gateway retries, out-of-order delivery, and Salesforce API governor limits.
   * Explicit architectural assumptions and three Architecture Decision Records (ADRs).
   * OpenAPI 3.1 contract for inbound webhooks and JSON Schema (`v1`) for canonical downstream events.
   * Root-cause code review of `PR-214` linked to production incident `INC-0412`.

2. **Part B — Practical Microservice (`practical/`):**
   * Standalone **Java 21 / Spring Boot 3** service implementing **Hexagonal Architecture** (Ports & Adapters).
   * **Cryptographic Authentication:** Constant-time (`MessageDigest.isEqual`) HMAC-SHA256 verification per gateway (`X-Andina-Signature` in Base64, `X-Quetzal-Signature` in Hex) with a `±300s` replay tolerance window.
   * **Provider Normalization:** Strategy pattern (`AndinaPaymentStrategy`, `QuetzalPaymentStrategy`) mapping heterogeneous payloads into `CanonicalPaymentEvent` and normalizing policy numbers to `POL-<COUNTRY>-<9_DIGITS>`.
   * **Idempotency & Ordering:** Atomic deduplication via `idempotencyKey` and state-regression protection via `PaymentLifecycleState` (`INITIAL` -> `PENDING` -> `CAPTURED` / `FAILED` / `REFUNDED`).
   * **Contract Validation & Error Handling:** Typed Java records with Jakarta Bean Validation (`@NotBlank`, `@Positive`) and standardized RFC 7807 `ProblemDetail` responses.
   * **PII-Safe Observability:** Propagates `X-Correlation-Id` through SLF4J `MDC` and strips payer personal data from canonical events and logs.

---

## Repository Structure

```text
.
├── docs/
│   ├── ASSUMPTIONS.md                                   # Explicit assumptions and open questions
│   ├── adr/
│   │   ├── 0001-salesforce-integration-pattern.md       # Downstream CRM decoupling and rate-limit protection
│   │   ├── 0002-implementation-stack-and-datastore.md   # Runtime stack, Cosmos DB partitioning, and idempotency
│   │   └── 0003-messaging-topology-and-dlq-replay.md    # Azure Service Bus topics, DLQ handling, and replay
│   ├── api/
│   │   ├── webhooks.openapi.yaml                        # OpenAPI 3.1 contract for webhook intake
│   │   └── events/
│   │       └── premium-payment.schema.json              # Canonical payment event JSON Schema (v1)
│   └── design/
│       └── SOLUTION_DESIGN.md                           # Architecture overview, flows, and security controls
├── pr-review/
│   └── REVIEW.md                                        # Code review findings and fixes for PR-214
├── practical/
│   ├── README.md                                        # Detailed service guide
│   ├── pom.xml                                          # Maven configuration (Java 21 + Spring Boot 3)
│   ├── requests.http                                    # Sample HTTP requests
│   └── src/
│       ├── main/
│       │   ├── java/com/meridian/payments/
│       │   │   ├── adapter/out/memory/                  # Thread-safe in-memory store and publisher adapters
│       │   │   ├── controller/                          # REST endpoints and RFC 7807 ProblemDetail handler
│       │   │   ├── dto/                                 # Typed request/response records with Bean Validation
│       │   │   ├── model/                               # CanonicalPaymentEvent and PaymentLifecycleState
│       │   │   ├── port/                                # Output ports (PaymentStorePort, EventPublisherPort)
│       │   │   ├── security/                            # Constant-time HMAC-SHA256 signature verifier
│       │   │   ├── service/                             # Orchestration and policy reference normalizer
│       │   │   └── strategy/                            # Gateway strategies (Andina Pay, Quetzal Pagos)
│       │   └── resources/
│       │       └── application.yml                      # Server port, MDC log pattern, and webhook secrets
│       └── test/
│           └── java/com/meridian/payments/
│               ├── WebhookIntakeIntegrationTest.java    # End-to-end MockMvc tests (202, 200, 400, 401)
│               └── service/
│                   └── PolicyNumberNormalizerTest.java  # Unit tests for CO/MX policy normalization
├── AI_USAGE.md                                          # Summary of AI-assisted workflows and verification
├── SUBMISSION.md                                        # Delivery summary and reviewer notes
└── SUBMISSION_CHECKLIST.md                              # Final verification checklist
```

---

## Prerequisites

* **Java 21+**
* **Maven 3.9+** (or VS Code with *Extension Pack for Java* / IntelliJ IDEA)
* `curl` and `openssl` (standard on macOS/Linux) for testing HMAC-signed requests

---

## How to Run the Application

### Option 1: From Terminal (Maven)
```bash
mvn -f practical/pom.xml spring-boot:run
```

### Option 2: From VS Code / IntelliJ
1. Open `practical/src/main/java/com/meridian/payments/PaymentsIntakeApplication.java`.
2. Click **Run** above the `main` method.

The server starts on `http://localhost:8080`. Verify it is running:
```bash
curl -i http://localhost:8080/v1/health
```
Expected response: `200 OK` with body `payments-intake is running`.

---

## How to Run the Tests

The automated test suite includes:
* `PolicyNumberNormalizerTest`: Unit tests verifying Colombian and Mexican policy reference normalization (`POL-<COUNTRY>-<9_DIGITS>`) and rejection of malformed inputs.
* `WebhookIntakeIntegrationTest`: Full HTTP integration tests (`@SpringBootTest` + `MockMvc`) verifying valid webhook ingestion (`202 Accepted`), idempotent replay suppression (`200 OK` / `DUPLICATE`), out-of-order state regression protection (`200 OK` / `OUT_OF_ORDER_IGNORED`), invalid HMAC rejection (`401 Unauthorized`), and Bean Validation errors (`400 Bad Request` RFC 7807 `ProblemDetail`).

### Option 1: From Terminal (Maven)
```bash
mvn -f practical/pom.xml test
```

### Option 2: From VS Code
1. Open the **Testing** panel in the left sidebar (`Cmd + ; A` on macOS).
2. Click **Run Tests** to execute all tests across `PolicyNumberNormalizerTest` and `WebhookIntakeIntegrationTest`.

---

## Testing the Endpoints Locally (HMAC-Signed Requests)

Every request to `POST /v1/webhooks/{provider}` requires a valid HMAC-SHA256 signature computed over `<unix_timestamp>.<raw_json_body>` within a `±300s` window.

Default local secrets in `practical/src/main/resources/application.yml` (overridable via `ANDINA_WEBHOOK_SECRET` and `QUETZAL_WEBHOOK_SECRET`):
* **Andina Pay:** `local-andina-dev-secret`
* **Quetzal Pagos:** `local-quetzal-dev-secret`

### 1. Andina Pay (Colombia — Base64 HMAC-SHA256) -> `202 Accepted`
```bash
TS=$(date +%s)
BODY='{"notificacion_id":"notif-co-001","transaccion":{"id":"TRX-CO-5521907","estado":"APROBADA","valor":450000,"moneda":"COP","referencia":"POL-CO-88412","fecha_transaccion":"2026-11-19T18:00:00-05:00"}}'
SIG=$(printf "%s.%s" "$TS" "$BODY" | openssl dgst -sha256 -hmac "local-andina-dev-secret" -binary | base64)

curl -i -X POST http://localhost:8080/v1/webhooks/andina \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: corr-andina-001" \
  -H "X-Andina-Timestamp: $TS" \
  -H "X-Andina-Signature: $SIG" \
  -d "$BODY"
```

### 2. Quetzal Pagos (Mexico — Hex HMAC-SHA256) -> `202 Accepted`
```bash
TS=$(date +%s)
BODY='{"id":"evt_mx_99102","type":"charge.succeeded","created":'$TS',"data":{"charge":{"id":"ch_mx_4410","amount":125000,"currency":"MXN","reference":"POL MX 30219","paid_at":'$TS'}}}'
V1=$(printf "%s.%s" "$TS" "$BODY" | openssl dgst -sha256 -hmac "local-quetzal-dev-secret" | awk '{print $NF}')

curl -i -X POST http://localhost:8080/v1/webhooks/quetzal \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: corr-quetzal-001" \
  -H "X-Quetzal-Signature: t=$TS,v1=$V1" \
  -d "$BODY"
```

### 3. Invalid Signature Check -> `401 Unauthorized`
```bash
TS=$(date +%s)
curl -i -X POST http://localhost:8080/v1/webhooks/quetzal \
  -H "Content-Type: application/json" \
  -H "X-Quetzal-Signature: t=$TS,v1=deadbeef" \
  -d '{"id":"evt_invalid"}'
```

---

## Key Documentation Links

* [Assumptions & Open Questions](docs/ASSUMPTIONS.md)
* [Solution Design](docs/design/SOLUTION_DESIGN.md)
* [ADR-0001: Salesforce Integration Pattern](docs/adr/0001-salesforce-integration-pattern.md)
* [ADR-0002: Implementation Stack and Datastore](docs/adr/0002-implementation-stack-and-datastore.md)
* [ADR-0003: Messaging Topology and DLQ Replay](docs/adr/0003-messaging-topology-and-dlq-replay.md)
* [OpenAPI 3.1 Contract](docs/api/webhooks.openapi.yaml)
* [Canonical Event Schema](docs/api/events/premium-payment.schema.json)
* [PR-214 Code Review](pr-review/REVIEW.md)
