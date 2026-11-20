# Incident Response — INC-0412 (Colombian Paid Policies Still Suspended)

## 1. First 30 Minutes

1. **00–05 min — Scope the impact and check Salesforce API budget:**
   * Check Service Bus queue and DLQ metrics for all three countries. Confirm Mexico (`DLQ=2`) and Chile (`DLQ=0`) are healthy and the spike is isolated to `payments-co` (`4,312` messages in DLQ).
   * Note that Salesforce 24h API usage jumped from `38%` to `66%` (`43,120` `400` errors in Colombia = `4,312` failed messages × `10` immediate retries). We are not hitting rate limits yet, so we have room to fix cleanly without shutting down traffic.
2. **05–15 min — Inspect the DLQ messages and consumer logs:**
   * Peek messages in `payments-co/$deadletterqueue` (read-only, without completing them) and search Application Insights by `correlationId` (`7f3a0c2e-…`).
   * Group DLQ items by `lastError` and `estado` (`4,198` `APROBADA`, `61` `REVERSADA`, `53` `RECHAZADA`).
3. **15–25 min — Check Friday and Saturday changes:**
   * **Friday `v1.14.0` deploy (`18:05`):** Only touched `INFO` logging format; `32,880` Colombian payments still succeeded (`sfStatus=200`), so this is not a deployment regression.
   * **Saturday Salesforce release (`22:00`):** Added a validation rule on `Policy__c` when cancelling a policy. That does not affect `Premium_Payment__c` upserts or `Policy_Number__c` lookups.
4. **25–30 min — Update stakeholders:**
   * Let the Colombian Contact-Center Lead know that no payments were lost—all `4,198` approved payments are safely sitting in `payments-co/$deadletterqueue` and will be reprocessed once we patch the policy number formatting.

---

## 2. Root Cause & Evidence

* **Root cause:** The v1 consumer sends Andina Pay's raw `referencia` field directly to Salesforce as the `Policy_Number__c` external ID without normalizing it.
* **Evidence from `payments-co/$deadletterqueue`:**
  * `referencia="000215560"` failed with `400 INVALID_FIELD: Foreign key external ID: 000215560 not found for field Policy_Number__c in entity Policy__c` because it lacks the `POL-CO-` prefix.
  * `referencia="pol-co-000198004"` failed with the same `400 INVALID_FIELD` because Salesforce external IDs are case-sensitive and expect uppercase `POL-CO-000198004`.
  * Starting Saturday `2026-11-28 23:41`, Andina Pay started sending notifications with unprefixed or lowercase policy references, and the v1 consumer had no normalization step.
* **Why API usage spiked:** The v1 consumer treats `400 INVALID_FIELD` as a transient failure and abandons the message immediately, burning all 10 delivery attempts in ~2.5 seconds (`23:41:07.412` to `23:41:09.902`) before moving it to the DLQ.

**How to confirm:** Query `Policy__c` in Salesforce for `Policy_Number__c IN ('POL-CO-000215560', 'POL-CO-000198004')` to verify those policies exist in uppercase canonical format.

---

## 3. Remediation & Safe DLQ Replay

### Step 1 — Fix the consumer before touching the DLQ
1. **Normalize policy references:** Apply canonical normalization (`trim` -> `uppercase` -> extract digits -> zero-pad to 9 digits -> format as `POL-CO-<9_DIGITS>`) before building the Salesforce upsert payload.
2. **Stop retrying permanent `400` errors:** If Salesforce returns `400 INVALID_FIELD` or `MALFORMED_ID`, send the message straight to the DLQ on the first attempt instead of retrying 10 times. Only retry transient `429` or `5xx` errors with backoff.

### Step 2 — Replay `payments-co/$deadletterqueue` in chronological order
We **must not** replay the DLQ in parallel. Look at `TRX-CO-5603318` in the DLQ:
* First message (`2026-11-28T23:41:07`): `estado=APROBADA`
* Third message (`2026-11-29T16:02:31`): `estado=REVERSADA`

If `REVERSADA` runs before `APROBADA` (or gets overwritten), Salesforce could reinstate a policy whose payment was already refunded.

1. **Deploy the patched consumer** and confirm live Colombian traffic with lowercase/unprefixed references returns `sfStatus=200`.
2. **Run a controlled replay script** that reads `payments-co/$deadletterqueue`, groups messages by `transaccion.id`, sorts each group by `fecha_transaccion` ascending, and re-enqueues them sequentially into `payments-co`.
3. **Rate-limit the replay** to `15 messages/second` (~5 minutes total for `4,312` messages) so Salesforce API usage stays safely below the 150k daily cap.
4. **Verify closure:** Confirm `payments-co/$deadletterqueue` drops to `0`, spot-check the 37 customer cases reported by the contact center to verify policy reinstatement, and close the incident.

---

## 4. Prevention

1. **Add DLQ alerts to all countries:** Currently only `payments-mx` has a DLQ alert from the pilot. Deploy the same `DeadLetteredMessageCount > 0` alert for `payments-co` and `payments-cl` via IaC.
2. **Normalize at ingestion:** Enforce `POL-<COUNTRY>-<9_DIGITS>` normalization in the intake layer and reject unparseable references early.
3. **Separate `4xx` from `5xx` in retry policies:** Never retry deterministic validation errors against Salesforce.
4. **Regression tests:** Add unit tests with `000215560` and `pol-co-000198004` to the Colombian mapper suite.
