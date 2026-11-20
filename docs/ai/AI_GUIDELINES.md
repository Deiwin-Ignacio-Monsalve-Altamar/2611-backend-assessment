# Squad Guidelines: Using AI Tools Safely

## 1. What Is Allowed vs. Never Shared

### Good uses of AI assistants
* Generating initial boilerplate (Java records, Spring controllers, Bicep skeletons, JUnit test setups).
* Drafting OpenAPI schemas, ADRs, and runbook documentation.
* Reviewing edge cases in regex patterns, SQL/SOQL queries, or stack traces.

### Never paste into external AI tools
* **Customer PII:** Names, national ID numbers (`cédula`, `CURP`, `RUT`), emails, phone numbers, or real policy numbers tied to a person.
* **Secrets:** Webhook HMAC secrets, OAuth client secrets, Azure connection strings, or API tokens.
* **Raw production logs or payloads:** Always sanitize logs or use synthetic payloads (like the samples in `reference/providers/`) before asking an AI tool for help.

---

## 2. How We Validate AI-Generated Code

Treat AI output the same way you treat a draft from a new teammate:
1. **Read every line before committing:** Make sure you understand how signatures, idempotency keys, and state transitions work—do not merge black-box code.
2. **Test failure paths, not just happy paths:** Every AI-assisted change must include unit or integration tests proving invalid signatures (`401`), bad payloads (`400`), duplicates (`200`), and out-of-order events behave properly.
3. **Pass CI gates:** Code must pass automated tests, schema validation, and secret scanning before requesting peer review.

---

## 3. Disclosing AI Use in Pull Requests

* When AI was used to generate or refactor non-trivial code, add one short line to the PR description (for example: *"Used Gemini to scaffold the initial Spring controller and test cases; manually wrote the state machine logic and verified all edge cases"*).
* The engineer opening the PR is fully responsible for the code in production.

---

## 4. Guardrails When Our Own Services Call an LLM

If Operations asks for an internal feature powered by an LLM (for example, summarizing dead-lettered messages in `payments-co/$deadletterqueue` during an incident), the service must meet these minimum rules:

1. **Strip PII before calling the model:** Remove customer names, IDs, emails, and raw provider payloads in code before building the prompt. Send only safe operational fields (`country`, `provider`, `paymentKey`, `status`, `sfErrorCode`).
2. **Use an approved private deployment:** Call Meridian's Azure OpenAI instance inside our VNet with zero data retention and model training disabled.
3. **Read-only output, never auto-remediation:** Force structured JSON output for human reading only. An LLM must never automatically replay DLQ messages or modify records in Salesforce without an engineer approving the action.
4. **Protect against prompt injection:** Wrap external gateway error strings (`lastError`, `motivo_reverso`) in clear data delimiters so unexpected text in a provider response cannot alter the prompt instructions.
5. **Timeouts and fallbacks:** Set a strict timeout (`<= 5s`) and circuit breaker so an LLM slowdown never blocks normal queue inspection or payment ingestion.
