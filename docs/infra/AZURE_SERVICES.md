# Azure Services & Cost Estimate

## 1. Service Selection & Well-Architected Trade-Offs

| Service | Tier / SKU | Why We Chose It | What We Discarded | Well-Architected Pillar |
|---|---|---|---|---|
| **Azure App Service (Linux)** | `P1v3` in `prod` (`B1` in `dev`/`test`) | Simple managed runtime for Java 21 / Spring Boot 3 with built-in TLS, Managed Identity, and easy horizontal scaling during month-end bursts (`40 req/s`). | **AKS** (too much cluster management overhead for a small squad) and **Azure Functions** (cold starts and extra binding friction for Spring Boot). | **Operational Excellence & Performance:** Simple deployment model with `alwaysOn` enabled in production to avoid cold starts. |
| **Azure Service Bus** | Standard | Gives us native Dead-Letter Queues (DLQ), retry limits (`maxDeliveryCount`), and a pub/sub topic (`canonical-payment-events`) so Finance and Notifications can subscribe independently. | **Azure Storage Queues** (no native topics/subscriptions) and **Event Hubs** (built for high-throughput telemetry streams rather than per-message DLQ workflows). | **Reliability:** Decouples webhook intake (`202 Accepted`) from Salesforce availability (`97%`) and API rate limits. |
| **Azure Cosmos DB (NoSQL)** | Serverless | Fast point reads/writes by `/paymentKey`, optimistic concurrency (`ETag`) to reject out-of-order states (`FR5`), automatic 90-day TTL, and pay-per-request pricing that fits spiky month-end traffic. | **Azure SQL** (requires schema migrations and connection pool sizing for 40+ req/s bursts). | **Cost Optimization & Reliability:** No wasted idle capacity on normal days (15k/day) while handling 4-day month-end peaks (120k/day). |
| **Azure Key Vault** | Standard | Keeps gateway HMAC secrets and Salesforce OAuth credentials out of source code and environment variables (`NFR6`). | Plain App Settings (secrets visible in portal/deployment history). | **Security:** Accessed exclusively via Managed Identity with RBAC. |
| **Application Insights & Monitor** | Pay-as-you-go (linked to platform Log Analytics) | End-to-end `correlationId` tracing (`NFR7`) and automated DLQ alerts across all three countries so failures never go unnoticed. | Self-hosted logging stack (redundant when platform Log Analytics already exists). | **Operational Excellence:** Directly prevents silent DLQ accumulation like `INC-0412`. |

---

## 2. Monthly Production Cost Estimate (Order of Magnitude)

Based on **NFR1** volume (~26 normal days at `15,000` notifications/day + 4 peak days at `120,000` notifications/day = **~870,000 notifications/month**):

| Service | Sizing Assumption | Estimated Monthly Cost (USD) |
|---|---|---|
| **Azure App Service (`P1v3`)** | 1–2 Linux instances running 24/7 (scaled out during the 4 peak days) | ~$95 |
| **Azure Service Bus (Standard)** | Base tier + ~3M queue/topic operations | ~$15 |
| **Azure Cosmos DB (Serverless)** | ~2M read/write operations/month + `< 5 GB` storage with 90-day TTL | ~$10 |
| **Azure Key Vault (Standard)** | `< 50,000` secret reads/month (secrets cached in app memory) | ~$2 |
| **Application Insights / Monitor** | ~5 GB structured logs/month + 3 country DLQ alerts | ~$15 |
| **Total Estimated Monthly Cost** | | **~$137 / month** |

*Note: The enterprise API Manager and shared Log Analytics workspace are pre-existing platform services and are excluded from this squad estimate.*
