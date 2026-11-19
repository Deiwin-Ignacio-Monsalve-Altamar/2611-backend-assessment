# API & Event Contracts

This folder holds the external webhook contracts and the internal canonical event schema:

- `webhooks.openapi.yaml` — OpenAPI 3.1 contract for the inbound provider webhooks exposed via Azure API Management.
- `events/premium-payment.schema.json` — JSON Schema (`v1.0`) for the canonical payment event published to Azure Service Bus (`payment-events`).

## How Contracts Evolve Safely

### Inbound Provider Webhooks (Tolerant Reader)
Providers regularly add new optional fields or event types (as seen in Andina Pay v2.3 adding `transaccion.canal` and unnormalized references).
- **Ignore unknown fields:** Our JSON deserializers are configured with `FAIL_ON_UNKNOWN_PROPERTIES = false` so additive provider changes never break ingestion.
- **Isolate breaking changes:** Each provider lives behind its own `PaymentProviderStrategy`. If a provider rolls out a breaking payload or signature change (e.g., `/v2/webhooks/andina`), we deploy a new strategy version side-by-side until the provider completes migration.

### Canonical Event Schema (`schemaVersion`)
Downstream consumers (Salesforce writer, Finance, Notifications) depend on `premium-payment.schema.json`.
- **Additive changes (minor, e.g., `1.0` -> `1.1`):** Adding optional nullable properties is backward-compatible. Consumers ignore unknown properties, and `schemaVersion` stays within the `1.x` line.
- **Breaking changes (major, e.g., `1.0` -> `2.0`):** Renaming fields, changing types, or removing required attributes requires incrementing the major `schemaVersion` (`"2.0"`), publishing dual-version events during a deprecation window, or routing via Service Bus subscription filter rules on `schemaVersion` until all downstream subscribers migrate.
