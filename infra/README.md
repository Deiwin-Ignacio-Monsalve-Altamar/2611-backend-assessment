# Azure Infrastructure as Code (`infra/`)

This folder contains the **Bicep** template (`main.bicep`) defining the Azure resources for Meridian Mutual's payment notifications platform.

## Resources Defined

* **Messaging (Azure Service Bus Standard):** Creates one queue per country (`payments-mx`, `payments-co`, `payments-cl`) with dead-lettering enabled (`maxDeliveryCount: 5`), plus the `canonical-payment-events` topic with subscriptions for Finance and Notifications.
* **State & Idempotency (Azure Cosmos DB Serverless):** Creates the `payments` database and `payment_states` container partitioned by `/paymentKey` with a 90-day TTL.
* **Compute & Identity (Azure App Service Linux — Java 21):** Runs the Spring Boot intake service behind the enterprise API Manager using a System-Assigned Managed Identity with least-privilege RBAC roles (`Key Vault Secrets User` and `Azure Service Bus Data Owner`).
* **Secrets (Azure Key Vault):** Stores gateway HMAC secrets and Salesforce OAuth credentials outside application code and config files (`NFR6`).
* **Observability (Application Insights & Azure Monitor):** Connects telemetry to the shared Log Analytics workspace and deploys a DLQ metric alert (`DeadletteredMessages > 0`) for **all three countries** (`mx`, `co`, `cl`).

## Parameters

* `environment`: `dev`, `test`, or `prod` (selects `B1` SKU for non-prod and `P1v3` with `alwaysOn` for `prod`).
* `countries`: `['mx', 'co', 'cl']` (adding a fourth country code automatically provisions its queue and DLQ alert).

## Validation

Per assessment instructions, resources are **not deployed**. The template is validated in CI using:

```bash
az bicep build --file infra/main.bicep
