# Solution Design — Premium Payment Notifications

## 1. High-Level Approach

This design handles real-time payment webhooks from **Quetzal Pagos (MX)**, **Andina Pay (CO)**, and **Austral Pagos (CL)**, writes them to Salesforce (`Premium_Payment__c`), and publishes a single canonical event for Finance and Notifications.

Key ideas behind this setup:
- **Never call Salesforce during a webhook.** Andina expects a response in 5 seconds, while Salesforce can take up to 8 seconds or go down for 90 minutes. We validate the webhook, save the dedup key, push the event to Azure Service Bus, and return `202 Accepted` right away.
- **Batch writes to Salesforce.** Sending 120,000 individual requests on a peak day would burn through our 150,000 daily API limit. A background worker groups events into batches (up to 200 records per call) using Salesforce Composite Collections (see `ADR-0001`).
- **Strategy pattern per provider (NFR9).** Each provider has its own signature check, payload format, and reference rules. Putting that behind a `PaymentProviderStrategy` interface means adding a fourth country later won't touch the core flow.
- **State pattern for payment status (FR5).** Webhooks arrive out of order. Modeling `Pending`, `Captured`, `Failed`, and `Refunded` with explicit transition rules stops an old event from overwriting a newer one (like turning a `Refunded` payment back into `Captured`).

---

## 2. Context Diagram & Trust Boundaries

```mermaid
flowchart TB
    subgraph Internet["Trust Boundary 1: Public Internet"]
        QZ["Quetzal Pagos (MX)"]
        AN["Andina Pay (CO)"]
        AU["Austral Pagos (CL)"]
    end

    subgraph Perimeter["Trust Boundary 2: Azure Perimeter"]
        APIM["Azure API Management\n(TLS, WAF, Rate Limits)"]
    end

    subgraph InternalAzure["Trust Boundary 3: Meridian Azure VNet (Managed Identity)"]
        subgraph Compute["Azure Container Apps (Java 21 / Spring Boot 3)"]
            INTAKE["Payment Intake Service\n(Strategy Pattern + Dedup)"]
            SFWORKER["Salesforce Writer Worker\n(Batching + State Pattern + Circuit Breaker)"]
            OPSAPI["Ops Lookup API\n(FR7 Read-Only)"]
        end

        SB[("Azure Service Bus Topic\npayment-events")]
        SUB_SF["Sub: sub-salesforce-writer (+ DLQ)"]
        SUB_FIN["Sub: sub-finance"]
        SUB_NOT["Sub: sub-notifications"]

        COSMOS[("Azure Cosmos DB\n(Dedup & Payment State)")]
        KV[("Azure Key Vault\n(Secrets)")]
        MON["Azure Monitor / App Insights"]
        IDP["Meridian IdP (OIDC / OAuth2)"]
    end

    subgraph External["Trust Boundary 4: External SaaS & Internal Users"]
        SF["Salesforce Org\n(97% avail | 150k calls/24h)"]
        FIN["Finance Consumer"]
        NOTIF["Notifications Consumer"]
        OPS["Operations Team"]
    end

    QZ & AN & AU -->|"HTTPS POST + HMAC"| APIM
    APIM -->|"Managed Identity / OAuth2"| INTAKE
    INTAKE -->|"Read secrets"| KV
    INTAKE -->|"Dedup + save state"| COSMOS
    INTAKE -->|"Publish event"| SB

    SB --> SUB_SF
    SB --> SUB_FIN --> FIN
    SB --> SUB_NOT --> NOTIF

    SUB_SF -->|"Read & batch"| SFWORKER
    SFWORKER -->|"Check/update state (ETag)"| COSMOS
    SFWORKER -->|"OAuth2 Client Credentials\nPATCH Composite (<=200)"| SF

    OPS -->|"OIDC Token"| APIM --> OPSAPI
    OPSAPI -->|"Query by provider ID / policy"| COSMOS
    IDP -.->|"Tokens"| APIM
    INTAKE & SFWORKER & OPSAPI -.->|"Logs & Metrics (No PII)"| MON
