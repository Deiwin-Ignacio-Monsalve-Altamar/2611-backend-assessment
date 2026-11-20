# Responsible AI Guidelines

## 1. Data Privacy
- Never paste real customer data, production payloads, tokens, or Key Vault secrets into AI prompts.
- Always use fake test fixtures (like `POL-CO-88412` or `local-andina-dev-secret`) when generating code, schemas, or tests.

## 2. Manual Review Rules
- **Security:** Always review HMAC verification, replay checks, and secret handling by hand.
- **Concurrency & State:** Verify that deduplication (`paymentKey`) and state transitions (`PaymentLifecycleState`) are thread-safe and atomic.
- **Infrastructure & CI:** Validate all Bicep templates (`az bicep build`) and GitHub Actions workflows before merging.

## 3. Testing
- AI-assisted code must pass Spotless formatting, unit/integration tests, and the `>= 80%` JaCoCo coverage gate.
- Any significant AI assistance must be documented in `AI_USAGE.md`.