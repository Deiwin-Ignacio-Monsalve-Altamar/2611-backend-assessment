package com.meridian.payments.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.payments.model.CanonicalPaymentEvent;
import com.meridian.payments.service.PolicyNumberNormalizer;

/**
 * Strategy implementation for Quetzal Pagos (Mexico).
 */
@Component
public class QuetzalPaymentStrategy implements PaymentProviderStrategy {

    private final ObjectMapper objectMapper;
    private final PolicyNumberNormalizer policyNormalizer;

    public QuetzalPaymentStrategy(ObjectMapper objectMapper, PolicyNumberNormalizer policyNormalizer) {
        this.objectMapper = objectMapper;
        this.policyNormalizer = policyNormalizer;
    }

    @Override
    public String providerKey() {
        return "quetzal";
    }

    @Override
    public CanonicalPaymentEvent mapToCanonical(String rawBody, String correlationId) throws Exception {
        // 1. Parse raw JSON payload from Quetzal
        JsonNode root = objectMapper.readTree(rawBody);
        String providerEventId = root.path("id").asText();
        String eventType = root.path("type").asText();
        long createdEpoch = root.path("created").asLong();

        JsonNode charge = root.path("data").path("charge");
        String chargeId = charge.path("id").asText();
        long amountMinor = charge.path("amount").asLong();
        String currency = charge.path("currency").asText("MXN").trim().toUpperCase();
        String policyNumber = policyNormalizer.normalize(charge.path("reference").asText(), "MX");

        // 2. Convert minor units (centavos) to major MXN decimal string
        String amount = BigDecimal.valueOf(amountMinor)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                .toPlainString();

        // 3. Map Quetzal event type to canonical status
        String canonicalStatus = switch (eventType) {
            case "charge.succeeded" -> "CAPTURED";
            case "charge.failed" -> "FAILED";
            case "charge.refunded" -> "REFUNDED";
            default -> "PENDING";
        };

        // 4. Resolve authoritative status timestamp
        long statusEpoch = createdEpoch;
        if ("REFUNDED".equals(canonicalStatus) && charge.hasNonNull("refunded_at")) {
            statusEpoch = charge.path("refunded_at").asLong();
        } else if ("CAPTURED".equals(canonicalStatus) && charge.hasNonNull("paid_at")) {
            statusEpoch = charge.path("paid_at").asLong();
        }

        // 5. Build canonical event without customer email PII
        return new CanonicalPaymentEvent(
                CanonicalPaymentEvent.SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                correlationId,
                "QUETZAL",
                "MX",
                "QUETZAL:" + chargeId,
                providerEventId,
                chargeId,
                "QUETZAL:" + providerEventId,
                policyNumber,
                canonicalStatus,
                amount,
                currency,
                Instant.ofEpochSecond(statusEpoch),
                Instant.now());
    }
}
