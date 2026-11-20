# Practical Part — Payment Notification Intake (`payments-intake`)

Runnable **Java 21 / Spring Boot 3** microservice that ingests webhook payment notifications from **Andina Pay** (Colombia) and **Quetzal Pagos** (Mexico), validates signatures, normalizes payloads into a canonical event, enforces idempotency and state ordering, and publishes events asynchronously.

---

## Stack and Versions

- **Language:** Java 21 (LTS)
- **Framework:** Spring Boot `3.5.7` (`spring-boot-starter-web`, `spring-boot-starter-validation`)
- **Build & Quality Plugins:** Maven `3.9+`, Spotless (`google-java-format`), JaCoCo (`>= 80%` line coverage gate)
- **Testing:** JUnit 5, Spring Boot Test (`@SpringBootTest`, `MockMvc`)
- **Architecture:** Hexagonal Architecture (Ports & Adapters) + Strategy pattern per payment provider

---

## How to Build and Run the Tests

Run all commands from the repository root (or inside `practical/` without the `-f practical/pom.xml` flag):

### 1. Build and Check Formatting

```bash
mvn -f practical/pom.xml spotless:check
mvn -f practical/pom.xml clean compile
```

### 2. Run Unit & Integration Tests (with `>= 80%` Coverage Check)

```bash
mvn -f practical/pom.xml verify -Djacoco.haltOnFailure=true
```

- `PolicyNumberNormalizerTest`: Verifies canonical policy normalization (`POL-<COUNTRY>-<9_DIGITS>`, including unprefixed and lowercase inputs like `000215560` or `pol-co-000198004`) and rejects invalid formats.
- `WebhookIntakeIntegrationTest`: Tests `/v1/health`, valid Andina Pay and Quetzal Pagos webhooks (`202 Accepted`), duplicate suppression (`200 OK` / `DUPLICATE`), out-of-order state protection (`200 OK` / `OUT_OF_ORDER_IGNORED`), invalid/expired HMAC signatures (`401 Unauthorized`), and malformed/invalid payloads (`400 Bad Request` RFC 7807 `ProblemDetail`).

### 3. Run the Service Locally

```bash
mvn -f practical/pom.xml spring-boot:run
```

Verify the health endpoint on `http://localhost:8080`:

```bash
curl -i http://localhost:8080/v1/health
```

Expected response: `200 OK` with body `payments-intake is running`.

---

## Testing Webhooks Locally (HMAC-Signed `curl`)

Default test secrets in `src/main/resources/application.yml` (overridable via `ANDINA_WEBHOOK_SECRET` and `QUETZAL_WEBHOOK_SECRET`):

- **Andina Pay:** `local-andina-dev-secret`
- **Quetzal Pagos:** `local-quetzal-dev-secret`

### Andina Pay (Colombia — Base64 HMAC-SHA256)

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

### Quetzal Pagos (Mexico — Hex HMAC-SHA256)

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

---

## Project Structure

```text
practical/
├── pom.xml                                              # Maven dependencies, Spotless, and JaCoCo config
├── requests.http                                        # Sample HTTP requests for IDE REST clients
└── src/
    ├── main/
    │   ├── java/com/meridian/payments/
    │   │   ├── PaymentsIntakeApplication.java           # Spring Boot entry point
    │   │   ├── adapter/out/memory/                      # In-memory store and event publisher adapters
    │   │   ├── controller/                              # Webhook REST controller, health check, and RFC 7807 error handler
    │   │   ├── dto/                                     # Typed request/response records with Jakarta Bean Validation
    │   │   ├── model/                                   # CanonicalPaymentEvent and PaymentLifecycleState enum
    │   │   ├── port/                                    # Hexagonal output ports (PaymentStorePort, EventPublisherPort)
    │   │   ├── security/                                # Constant-time HMAC-SHA256 verifier with ±300s replay window
    │   │   ├── service/                                 # Intake orchestration and PolicyNumberNormalizer
    │   │   └── strategy/                                # Provider strategies (AndinaPaymentStrategy, QuetzalPaymentStrategy)
    │   └── resources/
    │       └── application.yml                          # Server port, MDC correlation log pattern, and local dev secrets
    └── test/
        └── java/com/meridian/payments/
            ├── WebhookIntakeIntegrationTest.java        # End-to-end MockMvc tests (202, 200, 400, 401)
            └── service/
                └── PolicyNumberNormalizerTest.java      # Unit tests for CO/MX policy normalization
```

---

## What Is Stubbed and How

Per the assessment rules (no real cloud resources or external APIs called), external dependencies are isolated behind Hexagonal output ports and replaced with thread-safe in-memory adapters:

1. **Idempotency & Payment State Store (`InMemoryPaymentStoreAdapter` implementing `PaymentStorePort`):**
   - Simulates Azure Cosmos DB using a `ConcurrentHashMap` keyed by `paymentKey` (`<PROVIDER>:<PROVIDER_TX_ID>`) and atomic `compute` operations.
   - Enforces duplicate detection (`DUPLICATE`) and monotonic state progression (`INITIAL -> PENDING -> CAPTURED / FAILED / REFUNDED`) so late out-of-order events (`OUT_OF_ORDER_IGNORED`) never overwrite a terminal state.
2. **Event Broker Publisher (`InMemoryEventPublisherAdapter` implementing `EventPublisherPort`):**
   - Simulates publishing `CanonicalPaymentEvent` messages to an Azure Service Bus topic by storing published events in a thread-safe list and logging the `eventId`, `paymentKey`, and `correlationId`.
3. **Gateway Secrets (`application.yml`):**
   - Uses fictional local dev secrets (`local-andina-dev-secret`, `local-quetzal-dev-secret`) injected via `@Value` properties instead of fetching from Azure Key Vault at runtime.
4. **Downstream Salesforce CRM Consumer:**
   - Not implemented inside this service; `payments-intake` stops at publishing the canonical event (`202 Accepted`) to keep webhook ingestion decoupled from Salesforce API rate limits.

---

## Known Gaps

1. **Production Cloud Adapters:** `InMemoryPaymentStoreAdapter` and `InMemoryEventPublisherAdapter` do not persist across restarts. In production, these ports would be backed by **Azure Cosmos DB** (unique key on `/paymentKey` + `_etag` optimistic concurrency) and **Azure Service Bus**.
2. **Key Vault Secret Rotation:** Currently supports one active secret per provider via environment variables; production requires dual-secret validation (`current` + `previous` versions from Azure Key Vault) during rotation windows.
3. **Austral Pagos (Chile) Strategy:** Only Andina Pay (`CO`) and Quetzal Pagos (`MX`) strategies are implemented here as requested in Part B; adding Chile requires implementing a third `PaymentProviderStrategy` bean without modifying the core intake service.
4. **Concurrency & Load Tests:** Functional integration tests cover happy paths and edge cases, but multi-threaded race-condition tests (concurrent identical webhooks hitting at the exact same millisecond) and load benchmarks are pending.
