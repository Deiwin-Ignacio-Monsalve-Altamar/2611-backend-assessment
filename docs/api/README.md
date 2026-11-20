# API & Event Contracts

## Inbound Webhooks (`webhooks.openapi.yaml`)
- **Format:** OpenAPI 3.1.0
- **Endpoints:** `POST /v1/webhooks/andina` (Colombia) and `POST /v1/webhooks/quetzal` (Mexico)
- **Security:** HMAC-SHA256 signature computed over `<unix_timestamp>.<raw_payload>` with a `±300s` replay window.
- **Status Codes:**
  - `202 Accepted`: Valid payment event saved and queued.
  - `200 OK`: Duplicate delivery (`DUPLICATE`) or old state ignored (`OUT_OF_ORDER_IGNORED`).
  - `400 Bad Request` / `401 Unauthorized`: RFC 7807 `application/problem+json` error response.

## Canonical Event (`events/premium-payment.schema.json`)
- **Version:** `1.0.0` (JSON Schema Draft 2020-12)
- **Partition Key:** `policyNumber` (`POL-<COUNTRY>-<9_DIGITS>`)
- **Deduplication Key:** `paymentKey` (`<PROVIDER>:<PROVIDER_TX_ID>`)
- **Privacy:** Payer personal data (names, IDs, emails) is removed before creating the canonical event.
