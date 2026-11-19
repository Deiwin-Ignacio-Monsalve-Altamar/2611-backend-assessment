package com.meridian.payments.model;

import java.time.Instant;

/**
 * Canonical payment event (v1.0) emitted after receiving a provider webhook.
 * Payer PII is excluded at intake.
 */
public record CanonicalPaymentEvent(
        String schemaVersion,
        String eventId,
        String correlationId,
        String provider,
        String country,
        String paymentKey,
        String providerEventId,
        String providerTransactionId,
        String idempotencyKey,
        String policyNumber,
        String status,
        String amount,
        String currency,
        Instant statusChangedAt,
        Instant receivedAt) {

    public static final String SCHEMA_VERSION = "1.0";
}
