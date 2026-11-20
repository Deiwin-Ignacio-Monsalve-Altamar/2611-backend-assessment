# Operations Guide — Premium Payment Notifications

## 1. SLIs and SLOs by Country

Based on **NFR2** (freshness) and **NFR3** (durability), we track two core indicators per country:

| Country | Provider | Durability SLI (NFR3) | SLO | Freshness SLI (NFR2) | SLO |
|---|---|---|---|---|---|
| **Mexico** | Quetzal Pagos | `persisted_events / total_2xx_webhooks` | **100%** (zero lost accepted webhooks) | Time from webhook `2xx` to Salesforce upsert (`p95` / `p99.5`) | `p95 <= 5 min`, `p99.5 <= 60 min` |
| **Colombia** | Andina Pay | `persisted_events / total_2xx_webhooks` | **100%** (zero lost accepted webhooks) | Time from webhook `2xx` to Salesforce upsert (`p95` / `p99.5`) | `p95 <= 5 min`, `p99.5 <= 60 min` |
| **Chile** | Austral Pagos | `persisted_events / total_2xx_webhooks` | **100%** (zero lost accepted webhooks) | Time from webhook `2xx` to Salesforce upsert (`p95` / `p99.5`) | `p95 <= 5 min`, `p99.5 <= 60 min` |

If durability ever drops below `100%` (meaning we returned `200`/`202` to a gateway but failed to save or enqueue the event), we treat it as a SEV-1 and freeze non-urgent deploys until fixed.

---

## 2. Tracing, Metrics, and Privacy (NFR6)

### How `correlationId` travels end to end
1. **API Manager & Intake:** Reads `X-Correlation-Id` from the request header (or generates a UUID if missing) and puts it into SLF4J `MDC` and the HTTP response header.
2. **Cosmos DB & Service Bus:** Saves `correlationId` in the payment state record and copies it to the Service Bus message `CorrelationId` property.
3. **Salesforce Consumer:** Logs `correlationId` on every upsert attempt and writes it to `External_Correlation_Id__c` on `Premium_Payment__c` so support can trace a record back to the original webhook.

### What we log and measure
* **Structured JSON logs (Application Insights):** `timestamp`, `level`, `country`, `provider`, `correlationId`, `paymentKey`, `idempotencyKey`, `status`, `httpStatus`, `sfErrorCode`, and `latencyMs`.
* **Custom metrics (Azure Monitor):**
  * `webhook.received.count` and `webhook.rejected.count` (tagged by `provider` and HTTP status `400`/`401`).
  * `servicebus.queue.depth` and `servicebus.dlq.depth` (tagged by `country`).
  * `salesforce.api.calls.24h_pct` (percentage of the 150,000 daily API call budget).
* **Never logged:** Raw webhook bodies, payer names, national IDs (`cédula`, `CURP`, `RUT`), emails, phone numbers, or webhook HMAC secrets.

---

## 3. Alerts

| Alert | Trigger | Severity | Who gets paged | First check |
|---|---|---|---|---|
| **DLQ Messages Present** | `DeadLetteredMessageCount > 0` for 5 min on **any** country queue (`co`, `mx`, `cl`) | **SEV-2** | On-call Backend Engineer | Peek dead-lettered messages and check `lastError` / `sfErrorCode`. |
| **Freshness Slowdown** | `p95` end-to-end latency `> 4 min` for 10 min in any country | **SEV-2** | On-call Backend Engineer | Check Service Bus backlog and Salesforce `5xx` / `429` error rates. |
| **Salesforce Budget High** | Rolling 24h Salesforce calls `>= 75%` of the 150k limit (`112,500` calls) | **SEV-2** | On-call Backend Engineer + Tech Lead | Look for retry loops on `400` errors; enable batching/throttling if needed. |
| **Signature Errors Spike** | `> 20` `401 Unauthorized` responses in 5 min for one gateway | **SEV-3** | On-call Backend Engineer | Check Key Vault secret rotation or gateway clock drift (`±300s` window). |
| **Intake Storage Failure** | Any `5xx` after signature validation passes | **SEV-1** | On-call Backend Engineer + Platform SRE | Check Cosmos DB and Service Bus health; gateways will retry `5xx`. |
