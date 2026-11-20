# Meridian Mutual — Payment Webhook Ingestion Platform

Backend engineering assessment for **Meridian Mutual**. This repository includes the system architecture, API and event contracts, a runnable **Java 21 / Spring Boot 3** microservice (`payments-intake`), Azure Bicep infrastructure, GitHub Actions CI/CD, operational runbooks, and the code review for `PR-214`.

---

## What Was Built

Meridian Mutual receives policy payment webhooks from **Andina Pay** (Colombia) and **Quetzal Pagos** (Mexico) and updates policy statuses in Salesforce CRM.

1. **Part A — Architecture & Design (`docs/`):**
   - End-to-end ingestion design for traffic spikes, gateway retries, out-of-order events, and Salesforce API rate limits (`docs/design/SOLUTION_DESIGN.md`).
   - Project assumptions (`docs/ASSUMPTIONS.md`) and three Architecture Decision Records (`docs/adr/`).

2. **Part B — Microservice & Contracts (`practical/`, `docs/api/`):**
   - OpenAPI 3.1 spec for inbound webhooks and JSON Schema (`v1`) for canonical events.
   - Standalone **Java 21 / Spring Boot 3** service built with **Hexagonal Architecture**:
     - **HMAC Verification:** Constant-time (`MessageDigest.isEqual`) HMAC-SHA256 validation per provider (`X-Andina-Signature` in Base64, `X-Quetzal-Signature` in Hex) with a `±300s` replay window.
     - **Provider Normalization:** Strategy pattern (`AndinaPaymentStrategy`, `QuetzalPaymentStrategy`) mapping payloads to `CanonicalPaymentEvent` and formatting policy IDs as `POL-<COUNTRY>-<9_DIGITS>`.
     - **Idempotency & Ordering:** Deduplication using `paymentKey` and state protection via `PaymentLifecycleState` (`INITIAL` -> `PENDING` -> `CAPTURED` / `FAILED` / `REFUNDED`).
     - **Validation & Errors:** Typed Java records with Jakarta Validation and RFC 7807 `ProblemDetail` responses.
     - **Observability:** Tracks `X-Correlation-Id` in SLF4J `MDC` and strips payer PII before logging or publishing.

3. **Part C — Azure IaC & CI/CD (`infra/`, `.github/workflows/`):**
   - Parameterized Bicep template (`infra/main.bicep`) for Azure Container Apps, Service Bus, Cosmos DB, Key Vault, and Application Insights across `dev`, `test`, and `prod`.
   - GitHub Actions workflow (`.github/workflows/ci-cd.yml`) running Spotless formatting, tests, `>= 80%` JaCoCo coverage, `az bicep build`, Gitleaks secret scanning (`.gitleaks.toml`), and OIDC deployments (`dev -> test -> prod`).

4. **Part D & E — Operations & PR Review (`docs/operations/`, `pr-review/`):**
   - Operational runbook for SLIs, secret rotation, and Service Bus DLQ replay (`docs/operations/OPERATIONS.md`).
   - Incident response and postmortem for `INC-0412` (`docs/operations/INC-0412-RESPONSE.md`) and code review of `PR-214` (`pr-review/REVIEW.md`).

5. **Part F — Responsible AI (`AI_USAGE.md`, `docs/ai/`):**
   - Transparent summary of how AI tools were used and verified (`AI_USAGE.md` and `docs/ai/AI_GUIDELINES.md`).

---

## Repository Structure

```text
.
├── .github/workflows/
│   └── ci-cd.yml                                        # CI gates + OIDC CD pipeline (dev -> test -> prod)
├── .gitleaks.toml                                       # Allowlist for assessment reference fixtures
├── docs/
│   ├── ASSUMPTIONS.md                                   # Project assumptions and open questions
│   ├── adr/
│   │   ├── 0001-salesforce-integration-pattern.md       # Async Salesforce decoupling and rate-limit protection
│   │   ├── 0002-implementation-stack-and-datastore.md   # Stack choice, Cosmos DB partitioning, and idempotency
│   │   └── 0003-messaging-topology-and-dlq-replay.md    # Service Bus topics, DLQs, and replay strategy
│   ├── ai/
│   │   └── AI_GUIDELINES.md                             # Team rules for safe AI usage
│   ├── api/
│   │   ├── README.md                                    # API and event contracts summary
│   │   ├── webhooks.openapi.yaml                        # OpenAPI 3.1 webhook specification
│   │   └── events/
│   │       └── premium-payment.schema.json              # Canonical payment event JSON Schema (v1)
│   ├── design/
│   │   └── SOLUTION_DESIGN.md                           # Architecture overview, diagrams, and security
│   ├── infra/
│   │   └── AZURE_SERVICES.md                            # Cloud service choices and trade-offs
│   └── operations/
│       ├── INC-0412-RESPONSE.md                         # Incident containment, recovery, and postmortem
│       └── OPERATIONS.md                                # SLIs, secret rotation, and DLQ replay steps
├── infra/
│   ├── README.md                                        # Bicep parameters and local validation
│   └── main.bicep                                       # Azure resources definition
├── pr-review/
│   └── REVIEW.md                                        # Review findings and fixes for PR-214
├── practical/
│   ├── README.md                                        # Service setup and local curl examples
│   ├── pom.xml                                          # Maven config (Java 21, Spring Boot 3, Spotless, JaCoCo)
│   ├── requests.http                                    # Sample HTTP requests
│   └── src/
│       ├── main/java/com/meridian/payments/             # Application source code
│       └── test/java/com/meridian/payments/             # Unit and MockMvc integration tests
├── AI_USAGE.md                                          # Disclosure of AI usage during the assessment
├── SUBMISSION.md                                        # Final delivery summary
└── SUBMISSION_CHECKLIST.md                              # Verification checklist
```

---

## Prerequisites

- **Java 21+**
- **Maven 3.9+**
- `curl` and `openssl` (to generate HMAC signatures locally)

---

## How to Run the Application

```bash
mvn -f practical/pom.xml spring-boot:run
```

The service runs on `http://localhost:8080`. Check the health endpoint:

```bash
curl -i http://localhost:8080/v1/health
```

Expected output: `200 OK` with body `payments-intake is running`.

---

## How to Run Tests & Quality Gates

Run code formatting check, tests, and the `>= 80%` JaCoCo coverage gate:

```bash
mvn -f practical/pom.xml spotless:check
mvn -f practical/pom.xml verify -Djacoco.haltOnFailure=true
```

---

## Testing Endpoints Locally (HMAC-Signed Requests)

Requests to `POST /v1/webhooks/{provider}` need an HMAC-SHA256 signature over `<unix_timestamp>.<raw_json_body>` within `±300s`.

Default local secrets in `practical/src/main/resources/application.yml`:

- **Andina Pay:** `local-andina-dev-secret`
- **Quetzal Pagos:** `local-quetzal-dev-secret`

### 1. Andina Pay (Colombia — Base64 HMAC) -> `202 Accepted`

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

### 2. Quetzal Pagos (Mexico — Hex HMAC) -> `202 Accepted`

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

## Documentation Index

- **Part A (Design):** [Solution Design](docs/design/SOLUTION_DESIGN.md) · [Assumptions](docs/ASSUMPTIONS.md) · [ADR-0001](docs/adr/0001-salesforce-integration-pattern.md) · [ADR-0002](docs/adr/0002-implementation-stack-and-datastore.md) · [ADR-0003](docs/adr/0003-messaging-topology-and-dlq-replay.md)
- **Part B (Contracts & Code):** [API Overview](docs/api/README.md) · [OpenAPI 3.1](docs/api/webhooks.openapi.yaml) · [Event Schema](docs/api/events/premium-payment.schema.json) · [Service Guide](practical/README.md)
- **Part C (IaC & CI/CD):** [Bicep Guide](infra/README.md) · [Azure Services](docs/infra/AZURE_SERVICES.md) · [CI/CD Workflow](.github/workflows/ci-cd.yml)
- **Part D & E (Ops & Review):** [Operations Runbook](docs/operations/OPERATIONS.md) · [INC-0412 Postmortem](docs/operations/INC-0412-RESPONSE.md) · [PR-214 Review](pr-review/REVIEW.md)
- **Part F (Responsible AI):** [AI Usage](AI_USAGE.md) · [AI Guidelines](docs/ai/AI_GUIDELINES.md)
