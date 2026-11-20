# Submission

## Candidate

| Field | Value |
|---|---|
| Candidate ID (provided by your recruiter) | `deiwin1987@gmail.com` |
| Final commit SHA | `3c05fd15881f651b3fd9136671353a38665ecaa6` (all code & docs; followed only by this `SUBMISSION.md` update) |
| Date and time of submission (with time zone) | 2026-11-20 07:19 COT (UTC-5) |

## Time spent

| Part | Approximate time |
|---|---|
| A. Integration and data design | 2.0 hrs |
| B. Integration component | 2.5 hrs |
| C. Azure IaC and CI/CD | 1.0 hr |
| D. Operations | 0.75 hr |
| E. Code review | 0.75 hr |
| F. Responsible AI | 0.5 hr |
| **Total** | **7.5 hrs** |

## Stack

- Practical part (Part B): Java 21 + Spring Boot 3.5.7 (Hexagonal Architecture, Jakarta Validation, RFC 7807 `ProblemDetail`, Spotless, JaCoCo `>= 80%`)
- Infrastructure as code: Azure Bicep (`infra/main.bicep`)
- Pipeline: GitHub Actions (`.github/workflows/ci-cd.yml` with OIDC `dev -> test -> prod` and Gitleaks)
- CI run link (optional): https://github.com/Deiwin-Ignacio-Monsalve-Altamar/2611-backend-assessment/actions

## Where to start reading

1. `docs/design/SOLUTION_DESIGN.md` and `docs/adr/` — Architecture for burst handling, idempotency, event ordering, and Salesforce CRM decoupling.
2. `practical/src/main/java/com/meridian/payments/` — Java 21 / Spring Boot implementation with constant-time HMAC verification and provider strategies.
3. `pr-review/REVIEW.md` and `docs/operations/INC-0412-RESPONSE.md` — Root-cause review of `PR-214` and operational runbook for `INC-0412`.

## Known gaps and what I would do next

1. **Cloud Persistence Adapters:** Swap the in-memory adapters (`adapter/out/memory/`) for real Azure Cosmos DB and Azure Service Bus adapters using the existing output ports.
2. **Dependency Vulnerability Scanning:** Add Trivy or Dependabot CVE blocking once Spring Boot transitive dependencies have clean patches or a VEX allowlist so builds stay fast and deterministic.
3. **Contract & Load Testing:** Add consumer-driven contract tests for the Salesforce consumer and load tests simulating high webhook retry bursts.
