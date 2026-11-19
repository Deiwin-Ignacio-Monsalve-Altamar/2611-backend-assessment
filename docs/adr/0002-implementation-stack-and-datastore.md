# ADR-0002: Implementation Stack (Java 21 + Spring Boot 3) and Operational State Store

- **Status:** Accepted
- **Date:** 2026-11-18

## Context

We need to choose:
1. The runtime stack for the payment intake and integration workers (Mule 4 vs. Java 21).
2. The persistence store for idempotency checks, out-of-order state resolution (FR4, FR5), and operational tracking/search (FR7).

Key drivers:
- **Throughput and latency:** Must sustain bursts of 40 webhooks/s (NFR1) and respond within 5 seconds for Colombia (NFR4).
- **Cryptographic and payload edge cases:** Each provider uses distinct HMAC schemes (hex vs. Base64, timestamp windows, multi-signature rotation in Quetzal, and regex normalization for Andina v2.3).
- **Operational queryability (FR7):** Operations staff must look up any notification by provider transaction/event ID and see its current status (`RECEIVED`, `PUBLISHED`, `RECORDED`, `FAILED`, `DEAD_LETTERED`) without grepping raw logs.
- **Cost efficiency (NFR8) and squad maintainability:** The squad already maintains Java 21 services, and Azure hosts the platform.

## Options considered

### Part 1: Integration Runtime Stack

#### Option 1A — MuleSoft (Mule 4 + DataWeave + MUnit)
- **Pros:** Enterprise integration platform already available in Meridian's ecosystem; strong out-of-the-box connectors for Salesforce.
- **Cons:** Heavy vCore licensing footprint when scaling horizontally for burst traffic; custom cryptographic header parsing (such as Quetzal's rotating `t=...,v1=...,v1=...` header and constant-time comparison) is verbose in DataWeave; slower CI feedback loop and higher memory overhead per worker compared to modern JVM runtimes.

#### Option 1B — Java 21 + Spring Boot 3 (deployed on Azure Container Apps)
- **Pros:** Native support for constant-time HMAC verification (`javax.crypto.Mac` + `MessageDigest.isEqual`), clean domain modeling with Java records and sealed interfaces, fast startup and lightweight scaling on Azure Container Apps, and rich testing with JUnit 5, Mockito, and WireMock/Testcontainers. Fits the Payments Integration squad's core Java skillset.
- **Cons:** Requires writing and maintaining our own Salesforce Composite REST client, OAuth2 token caching, and retry/circuit-breaker plumbing (via Resilience4j) rather than using a pre-built Mule connector.

### Part 2: Operational & Idempotency Data Store

#### Option 2A — Azure Cache for Redis only
- **Pros:** Sub-millisecond key lookups for deduplication.
- **Cons:** Volatile by default unless configured with expensive persistence tiers; poor fit for secondary-index queries required by Operations (FR7: searching by `providerTransactionId`, `policyNumber`, or `status` across 90 days).

#### Option 2B — Azure Cosmos DB for NoSQL
- **Pros:** Single-digit millisecond point reads/writes, native TTL for automatic 90-day cleanup, atomic conditional updates via ETags (solving concurrent duplicate races and out-of-order timestamp checks cleanly), and low cost at 15k–120k writes/day.
- **Cons:** Cross-partition queries can become costly if not indexed carefully; requires disciplined partition key selection.

#### Option 2C — Azure SQL Database (Relational)
- **Pros:** Strong ACID transactions and familiar SQL querying for Operations.
- **Cons:** Higher baseline cost for burst capacity; row-level lock contention under concurrent duplicate webhooks (`40 req/s`) requires careful `MERGE`/upsert tuning.

## Decision

We choose **Option 1B (Java 21 + Spring Boot 3 on Azure Container Apps)** paired with **Option 2B (Azure Cosmos DB for NoSQL)** as our operational and idempotency state store.

- **Java 21 + Spring Boot 3:**Isolates each provider behind a Strategy interface (PaymentProviderStrategy) resolved via a registry, and models payment lifecycle transitions using an explicit State pattern (PaymentStatus) so out-of-order events are rejected cleanly without touching the shared pipeline (FR5, NFR9).
- **Azure Cosmos DB:** Stores one tracking document per canonical payment (`id = paymentKey`, partitioned by `/country`) plus a lightweight deduplication marker (`id = dedup:<provider>:<eventKey>`, TTL = 90 days). Using Cosmos DB's `createItem` (HTTP `409 Conflict` on duplicate) gives atomic, lock-free deduplication at the edge, while ETag optimistic concurrency ensures out-of-order events never overwrite a newer `statusChangedAt` timestamp.

## Consequences

- **Positive:**
  - Atomic deduplication (`409 Conflict` on duplicate event key) eliminates race conditions when providers retry concurrently.
  - Built-in 90-day TTL automatically purges expired idempotency and tracking records with zero maintenance jobs.
  - Operations can query notification status directly by `providerTransactionId` or `policyNumber` via a secured internal endpoint backed by Cosmos DB indexes (FR7).
- **Negative:**
  - Team owns the Salesforce REST/Composite client code and Resilience4j policies instead of relying on MuleSoft's managed connector.
  - Cosmos DB queries that do not include the `/country` partition key result in fan-out queries; the Operations UI/API will require selecting the country (or derive it from the `POL-<CC>-` prefix) to keep RU consumption minimal.
- **Risks and how they are mitigated:**
  - *Risk:* Cosmos DB RU throttling (`429`) during month-end 40 req/s bursts. *Mitigation:* Use autoscale throughput (1,000–4,000 RU/s) and partition by `/country` (evenly distributing MX 45%, CO 40%, CL 15% across logical partitions).
