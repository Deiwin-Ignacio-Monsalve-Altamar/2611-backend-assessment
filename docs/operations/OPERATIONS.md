# Operations Runbook — Payment Webhook Platform

## 1. Service Level Indicators (SLIs) & Alerts

All queues/topics across **Colombia (`co`)**, **Mexico (`mx`)**, and **Chile (`cl`)** must have identical monitoring provisioned via IaC:

| Signal | Target / Healthy Baseline | Alert Threshold | Severity |
|---|---|---|---|
| Webhook Intake Latency (`POST /v1/webhooks/{provider}`) | `p95 < 250 ms` | `p95 > 800 ms` for 5 min | SEV-3 |
| Webhook `5xx` Error Rate | `< 0.1%` | `> 1%` for 5 min | SEV-2 |
| HMAC Signature Rejections (`401`) | `< 5 / min` | `> 25 / min` (key mismatch or attack) | SEV-2 |
| Service Bus DLQ Depth (per country) | `0` messages | `> 0` messages for 5 min | SEV-2 |
| Salesforce `400` Validation Errors | `0` messages | `> 10` in 5 min (indicates schema/mapping drift) | SEV-2 |
| Salesforce Rolling 24h API Quota | `< 50%` | `> 75%` warning / `> 85%` critical | SEV-2 |

---

## 2. Standard Operating Procedures (SOPs)

### SOP-1: Handling Service Bus Dead-Letter Queue (DLQ) Alerts
1. **Inspect, Do Not Blindly Replay:** Read sample messages from `<queue>/$deadletterqueue` using Azure Service Bus Explorer or CLI and check `DeadLetterReason` and `lastError`.
2. **Classify the Failure:**
   - **Transient Downstream Outage (`5xx` / `429`):** Wait until Salesforce recovers, then replay at a throttled rate (`<= 20 msg/sec`).
   - **Data / Mapping Error (`400 INVALID_FIELD`):** Fix the normalization/mapping bug first, deploy the patch, normalize the exported DLQ payloads, and then replay.
3. **Preserve Event Ordering:** When replaying multiple messages for the same `paymentKey` / `transaccion.id` (for example, `APROBADA` followed by `REVERSADA`), sort by event timestamp before re-enqueueing so final state in Salesforce is accurate.

### SOP-2: Zero-Downtime Webhook Secret Rotation
1. Generate a new 256-bit HMAC secret in **Azure Key Vault** as a new secret version.
2. Enable dual-secret validation in `payments-intake` (accepting both `current` and `previous` secret versions) for a 24-hour overlap window.
3. Share the new secret with the payment gateway (Andina Pay, Quetzal Pagos, or Austral) over an encrypted channel and confirm cutover.
4. Disable the old secret version in Key Vault and verify `401` signature failure metrics stay at baseline.

### SOP-3: Protecting Salesforce API Budget During Traffic Spikes
1. Monitor rolling 24h Salesforce API usage in Application Insights.
2. If quota exceeds `80%`, lower `maxConcurrentCalls` on the Service Bus consumer and enable batch/throttled processing so inbound webhooks continue returning `202 Accepted` without dropping payments.
