# Incident Response & Postmortem — INC-0412

## 1. Incident Overview
- **Incident ID:** `INC-0412`
- **Severity:** **SEV-2** (escalated operationally due to 4,312 unapplied Colombian payments, active customer impact on suspended policies, and 66% Salesforce API quota burn)
- **Opened:** Monday `2026-11-30 08:12` COT (UTC-5)
- **Affected Scope:** Colombia (`payments-co`) only. Mexico (`payments-mx`) and Chile (`payments-cl`) are healthy.

---

## 2. Root Cause Analysis

### Primary Root Cause
Starting Saturday `2026-11-28 23:41` COT, incoming webhooks from **Andina Pay** began sending unnormalized values in `transaccion.referencia` — specifically missing the country prefix (`"000215560"`) or using lowercase (`"pol-co-000198004"`). Because v1 passed `referencia` directly to Salesforce without canonical normalization (`POL-CO-<9_DIGITS>`), Salesforce rejected every upsert with:
> `400 INVALID_FIELD: Foreign key external ID: <referencia> not found for field Policy_Number__c in entity Policy__c`

### Contributing Factors (Why It Escalated)
1. **Blind Retries on Permanent `400` Errors:** The `payments-co` consumer treated `400 INVALID_FIELD` as a transient failure and immediately abandoned the message 10 times within ~2.5 seconds (`23:41:07.412` to `23:41:09.902`). That turned **4,312** broken notifications into **43,120** failed Salesforce API calls, pushing daily API quota consumption from **38% to 66%**.
2. **Missing Observability for Colombia & Chile:** A DLQ-depth alert was only configured for `payments-mx` during the Mexico pilot, leaving `payments-co` accumulating dead-lettered messages silently for ~32 hours.

### Ruled-Out Changes (Red Herrings)
- **Salesforce Release (`Sat 22:00`):** Added a page layout and a cancellation reason validation rule on `Policy__c`. Neither affects `Premium_Payment__c` upserts by `Policy_Number__c`.
- **Integration Deploy (`Fri 18:05`, `v1.14.0`):** Only changed `INFO` log formatting; failures started almost 30 hours later when Andina Pay's reference payload format shifted.

---

## 3. Immediate Triage & Containment (First 30 Minutes)

1. **Protect Salesforce API Quota:** Verify that `payments-co` main queue depth is `0` (already drained into DLQ) and **do not** blindly trigger a bulk DLQ replay before fixing normalization, as replaying 4,312 messages would burn another 43,120 API calls.
2. **Create Urgent Alerts for All Countries:** Immediately add Azure Monitor alerts for `DeadLetteredMessageCount > 0` on `payments-co` and `payments-cl` (matching `payments-mx`), plus an alert on Salesforce `400` error spikes (`> 50/5min`).
3. **Contact Andina Pay & Coordinate Customer Support:**
   - Notify Andina Pay's technical team about the `referencia` format change (`000215560` / `pol-co-...` instead of `POL-CO-000215560`).
   - Share the list of affected policy numbers from `payments-co/$deadletterqueue` with Colombia Contact Center so agents can manually verify receipts and temporarily unsuspend policies for the 37+ affected customers while automated replay is prepared.

---

## 4. Safe DLQ Recovery & Replay Plan

> **Critical Ordering Warning:** Inspecting `payments-co/$deadletterqueue` reveals multiple lifecycle events for the same transaction — for example, `TRX-CO-5603318` arrived first as `APROBADA` (`2026-11-28 23:41:07`) and later as `REVERSADA` (`2026-11-29 16:02:31`). Replaying messages out of order or without consolidation could falsely activate a policy whose payment was already reversed.

### Step-by-Step Recovery
1. **Deploy Hotfix (`v1.14.1`):**
   - Apply `PolicyNumberNormalizer` (`POL-CO-<9_DIGITS>`, trimming whitespace, uppercasing, and left-padding digits with zeros when prefix is omitted) before publishing/upserting.
   - Classify HTTP `400` responses from Salesforce as **non-retryable poison/validation errors** and route them straight to DLQ on attempt 1 instead of retrying 10 times.
2. **Export & Deduplicate DLQ Messages Before Replay:**
   - Read all **4,312** messages from `payments-co/$deadletterqueue` (`4,198 APROBADA`, `61 REVERSADA`, `53 RECHAZADA`) into a staging script/file.
   - Normalize `referencia` to `POL-CO-<9_DIGITS>` and validate each policy ID against Salesforce `Policy__c` in read-only bulk query first (quarantining any genuinely non-existent policy numbers for manual Finance review).
   - Group messages by `transaccion.id` and sort by event timestamp so state transitions (`APROBADA` -> `REVERSADA`) execute in strict chronological order.
3. **Throttled Replay:**
   - Re-enqueue the normalized, ordered messages into `payments-co` at a controlled rate (**15–20 msg/sec**) while monitoring Salesforce API budget (`< 75%` ceiling) and confirming `0` `400 INVALID_FIELD` responses.
4. **Post-Replay Audit:**
   - Confirm `payments-co/$deadletterqueue` depth reaches `0` and reconcile the 37 customer support tickets with Colombia Contact Center.

---

## 5. Long-Term Preventative Actions

| Area | Action | Owner |
|---|---|---|
| **Input Normalization** | Enforce canonical `POL-<COUNTRY>-<9_DIGITS>` normalization at the webhook ingress boundary (`PolicyNumberNormalizer`) before events enter Service Bus. | Payments Squad |
| **Error Classification** | Separate transient errors (`429`, `502`, `503`, timeouts -> exponential backoff retry) from permanent errors (`400 INVALID_FIELD` -> immediate dead-letter + alert). | Payments Squad |
| **Multi-Country Monitoring** | Provision DLQ-depth alerts via Bicep (`infra/main.bicep`) automatically for every country queue/subscription (`co`, `mx`, `cl`) so pilot-only gaps cannot happen again. | Platform / SRE |
| **Contract Testing** | Add automated regression tests covering unprefixed (`000215560`) and lowercase (`pol-co-000198004`) provider references (`PolicyNumberNormalizerTest`). | Payments Squad |
