# ADR-0001: Asynchronous Batched Integration with Salesforce

- **Status:** Accepted
- **Date:** 2026-11-18

## Context

Meridian Mutual must record payment lifecycle updates from three countries into a single Salesforce org (`Premium_Payment__c`). Several hard constraints govern this integration:

1. **Provider timeouts (NFR4):** Andina Pay requires a `2xx` response within **5 seconds**; Quetzal and Austral require it within **10 seconds**.
2. **Salesforce latency and availability (NFR5):** Single-record upserts have a p95 latency of 1.2 s and can spike to 8 s under load. Monthly availability is ~97%, with unplanned outages lasting **5 to 90 minutes**.
3. **API budget and concurrency (NFR5):** This integration has a strict budget of **150,000 API calls per rolling 24 hours** (shared org-wide; exceeding it triggers `403 REQUEST_LIMIT_EXCEEDED` across all Meridian systems) and a maximum of **25 concurrent connections**.
4. **Month-end traffic spikes (NFR1):** Up to **120,000 notifications/day**, with sustained bursts of **40 notifications/second** for 15 minutes (36,000 events in one burst).
5. **Freshness (NFR2):** 95% of payments must appear in Salesforce within **5 minutes**, and 99.5% within **1 hour**.

If we call Salesforce synchronously from the webhook receiver—or make one REST call per payment—a latency spike in Salesforce will breach Andina's 5-second timeout, a 30-minute outage will drop incoming webhooks, and a month-end peak (120,000 events + retries) will exhaust our 150,000 daily API call budget.

## Options considered

### Option 1 — Synchronous pass-through (Webhook -> Salesforce single-record PATCH)
- **Pros:** Simplest architecture; no intermediate queue or worker needed; lowest latency when Salesforce is healthy.
- **Cons:** Violates NFR3/NFR4 during Salesforce slowdowns (8 s latency > 5 s Andina timeout) or 90-minute outages; consumes 1 API call per notification (120,000+ calls/day at peak, leaving almost zero margin for retries or other jobs).

### Option 2 — Asynchronous queue with single-record Salesforce upserts
- **Pros:** Decouples webhook ingestion from Salesforce availability; protects provider timeouts; survives 90-minute Salesforce outages via queue buffering.
- **Cons:** Still consumes 1 API call per payment (~116,400 calls/day after 3% deduplication on peak days). With retries during transient errors or lock contention (`UNABLE_TO_LOCK_ROW`), daily usage easily crosses the 150,000 call cap.

### Option 3 — Asynchronous queue with micro-batched Salesforce Composite Collections
- **Pros:** Decouples ingestion (sub-second `202 Accepted` after durable enqueue); uses `PATCH /services/data/v62.0/composite/sobjects/Premium_Payment__c/Payment_Key__c` (`allOrNone: false`) to upsert up to **200 records in a single API call**; reduces peak API consumption from ~120,000 calls/day to ~1,500–3,000 calls/day; respects the 25-connection limit using controlled worker concurrency and a circuit breaker.
- **Cons:** Introduces slight batching delay (up to 5–10 seconds flush window, well within the 5-minute NFR2 target); requires per-record result parsing because `allOrNone: false` returns partial successes/failures inside a `200 OK` HTTP response.

## Decision

We choose **Option 3: Asynchronous ingestion via Azure Service Bus followed by a micro-batching Salesforce writer using Composite Collections**.

- **Intake path:** The webhook receiver validates the signature, persists idempotency/tracking state, publishes the canonical event to Azure Service Bus, and immediately returns `200`/`202` (typically < 150 ms).
- **Salesforce writer:** A background worker reads from the Salesforce subscription/queue, aggregates records into micro-batches (flushing every **5 seconds** or **100 records**, whichever comes first, up to Salesforce's 200-record max), and sends them via the Composite Collections API with `allOrNone: false`.
- **Resilience controls:** Worker concurrency is capped at **5 concurrent batch requests** (well below the 25-connection ceiling). A circuit breaker opens on `5xx` or `403 REQUEST_LIMIT_EXCEEDED` responses, pausing consumption while Service Bus buffers messages safely during up to 90-minute outages. OAuth2 tokens are cached in memory and refreshed proactively before their 2-hour expiry.

## Consequences

- **Positive:**
  - Peak-day Salesforce API usage drops to **< 3,000 calls/day** (< 2% of the 150,000 daily budget), leaving massive headroom for retries and replays.
  - End-to-end latency stays around **5–15 seconds** under normal load, comfortably meeting the 5-minute SLA (NFR2).
  - Completely isolates providers from Salesforce outages.
- **Negative:**
  - Partial batch failures require inspecting each item in the Salesforce composite response array so only failed records are retried or dead-lettered.
  - Duplicate updates to the same `Payment_Key__c` inside a single batch are rejected by Salesforce (`DUPLICATE_VALUE` within the same request), so the batch assembler must coalesce events for the same `Payment_Key__c` before sending.
- **Risks and how they are mitigated:**
  - *Risk:* One malformed record in a batch causes confusion during debugging. *Mitigation:* `allOrNone: false` ensures valid records succeed; non-retryable `400` errors (`INVALID_FIELD`, `FIELD_CUSTOM_VALIDATION_EXCEPTION`) are routed directly to the dead-letter queue with the specific Salesforce error code attached, without retrying.
