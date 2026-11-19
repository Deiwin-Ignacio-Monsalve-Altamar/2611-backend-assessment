# ADR-0003: Azure Service Bus Topic Topology and Safe DLQ Replay

- **Status:** Accepted
- **Date:** 2026-11-18

## Context

Functional requirement **FR6** states that downstream consumers (Finance and Notifications) must consume the canonical payment event independently without tight coupling to our Salesforce writer. Additionally, **FR5** and **NFR3** require that failed or unprocessable messages are never lost, are isolated from healthy traffic (preventing poison-pill head-of-line blocking), and can be safely replayed after a fix (as highlighted by incident `INC-0412`).

## Options considered

### Option 1 — Single Azure Service Bus Queue shared by all consumers
- **Pros:** Minimal resource count and lowest configuration overhead.
- **Cons:** Competing consumers on a single queue mean Finance, Notifications, and Salesforce would steal messages from each other unless each country/consumer gets duplicated queues manually; hard to isolate a single country's failures.

### Option 2 — Azure Service Bus Topic (`payment-events`) with Filtered Subscriptions per Consumer
- **Pros:** Clean pub/sub fan-out: the intake service publishes the canonical event once to the `payment-events` topic. Independent subscriptions (`sub-salesforce-writer`, `sub-finance`, `sub-notifications`) receive their own copy and scale/fail independently. Each subscription has its own built-in Dead-Letter Queue (DLQ).
- **Cons:** Slightly higher per-operation cost (1 publish + N subscription deliveries) than a single queue, though still trivial (~$15–$25/month at Meridian's volume).

## Decision

We choose **Option 2: Azure Service Bus Standard/Premium Topic (`payment-events`) with dedicated subscriptions per downstream domain** (`sub-salesforce-writer`, `sub-finance`, `sub-notifications`).

- **Poison-message fast-fail:** The Salesforce writer distinguishes transient errors (`5xx`, `429`, `408`, `UNABLE_TO_LOCK_ROW`) from permanent business/validation errors (`400 INVALID_FIELD`, `FIELD_CUSTOM_VALIDATION_EXCEPTION`). Transient errors are retried with exponential backoff; permanent errors are immediately dead-lettered with structured `DeadLetterReason` and `DeadLetterErrorDescription` metadata instead of burning 10 wasted delivery attempts.
- **Safe DLQ replay:** Replayed messages re-enter the topic/subscription and pass through the exact same state-precedence check (`statusChangedAt` + state rank in Cosmos DB) before calling Salesforce. Therefore, replaying an older `Captured` event after a `Refunded` event has already been recorded is a safe no-op.

## Consequences

- **Positive:**
  - Adding a new downstream consumer (e.g., Fraud Analytics) requires adding a Service Bus subscription with zero changes to the intake service.
  - A Salesforce outage or a country-specific payload issue (like Andina v2.3) never blocks Finance or Notifications from receiving canonical events in real time.
- **Negative:**
  - Operational monitoring must track DLQ depth per subscription rather than a single global queue.
- **Risks and how they are mitigated:**
  - *Risk:* Service Bus Standard tier has shared multi-tenant latency spikes during 40 msg/s bursts. *Mitigation:* Use Service Bus Standard for dev/test and Premium (1 Messaging Unit) in prod for predictable latency and VNet integration.
