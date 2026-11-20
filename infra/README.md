# Azure Infrastructure as Code (`infra/main.bicep`)

This folder contains the Azure Bicep template for the **Meridian Mutual Payment Webhook Platform**. Nothing is deployed in this assessment; the template is validated via `az bicep build`.

## Provisioned Resources

- **Azure Container Apps Environment & App (`payments-intake`):** Runs the Java 21 container with HTTP concurrency autoscaling (`1` to `10` replicas) and a System-Assigned Managed Identity.
- **Azure Service Bus Namespace & Topic/Queues:** Decouples inbound webhook HTTP responses (`202 Accepted`) from downstream Salesforce CRM upserts, with Dead-Letter Queues (DLQs) enabled per country (`co`, `mx`, `cl`).
- **Azure Cosmos DB (NoSQL API):** Stores canonical payment state partitioned by `/policyNumber` with a unique key constraint on `/paymentKey` for atomic deduplication and `_etag` optimistic concurrency.
- **Azure Key Vault:** Stores gateway HMAC signing secrets (`andina-webhook-secret`, `quetzal-webhook-secret`), granted to the Container App via RBAC (`Key Vault Secrets User`) with zero stored secrets.
- **Log Analytics & Application Insights:** Centralizes structured JSON logs (`X-Correlation-Id`) and metrics.

## Parameters & Environments

The template is parameterized by `environment` (`dev`, `test`, `prod`), `location`, and `containerImageTag`:

| Parameter / Setting | `dev` | `test` | `prod` |
|---|---|---|---|
| Container App Min/Max Replicas | `1` – `2` | `1` – `4` | `2` – `10` |
| Service Bus SKU | `Standard` | `Standard` | `Premium` |
| Cosmos DB Consistency | `Session` | `Session` | `Strong` / `Session` |
| Deployment Approval Gate | Automatic on `main` | Automatic after `dev` | Manual approval + `what-if` preview |

## Local Validation

```bash
az bicep build --file infra/main.bicep
