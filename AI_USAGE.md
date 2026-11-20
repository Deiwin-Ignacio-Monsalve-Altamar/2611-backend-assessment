# AI usage disclosure

> **Mandatory.** AI assistants are allowed. What we value is how you governed their use.
> If you did not use any AI tool, tick the box below and leave the rest empty.

- [ ] I did not use any AI tool in this assessment.

## Tools

| Tool (name and version, if known) | Where you used it (part and files) | What you used it for |
|---|---|---|
| Gemini | Parts A–F (`docs/*`, `practical/*`, `infra/*`, `.github/workflows/ci-cd.yml`, `pr-review/REVIEW.md`) | Discussing architecture trade-offs, scaffolding initial Spring Boot/Bicep/test boilerplate, and polishing English phrasing in markdown docs. |

## Validation

- **Hands-on implementation and review:** I designed the hexagonal structure, state transition rules, HMAC verification flow, and incident remediation steps myself, checking every file line by line against `TASKS.md`, `SCENARIO.md`, and `reference/providers/`.
- **Automated test suite:** I ran `PolicyNumberNormalizerTest` and `WebhookIntakeIntegrationTest` locally (`MockMvc`) to verify signature rejections (`401`), contract validation (`400`), idempotent duplicates (`200`), and out-of-order state protection (`200`).
- **Live HTTP verification:** I ran the Spring Boot service locally and tested both `/v1/webhooks/andina` and `/v1/webhooks/quetzal` using `curl` and `openssl` to confirm end-to-end behavior.

## What I discarded or corrected

- **Simplified Azure IaC:** I rejected an overly complex Container Apps + custom RBAC template in favor of a cleaner Azure App Service (Linux, Java 21) + Service Bus + Cosmos DB Serverless Bicep file that is easier to maintain and explain.
- **English consistency in DTOs:** I refactored `AndinaWebhookRequest` to use English Java field names (`transaction`, `status`, `amount`) with `@JsonProperty` annotations instead of Spanish field names.
- **Simpler raw body handling:** I discarded a custom `ServletRequestWrapper` filter and used Spring's `@RequestBody String rawBody` directly in `WebhookController` for HMAC signature validation.
- **Policy padding alignment:** I updated `PolicyNumberNormalizerTest` to match the 9-digit zero-padded canonical format (`POL-CO-000088412`).

## Data I shared with AI tools

- Only the assessment instructions and sample provider payload structures from `reference/`.
- No real credentials, production secrets, or personal data (PII) were shared. Local tests used dummy secrets (`local-andina-dev-secret` and `local-quetzal-dev-secret`).