package com.meridian.payments.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.payments.dto.QuetzalWebhookRequest;
import com.meridian.payments.model.CanonicalPaymentEvent;
import com.meridian.payments.service.PolicyNumberNormalizer;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Strategy implementation for Quetzal Pagos (Mexico).
 */
@Component
public class QuetzalPaymentStrategy implements PaymentProviderStrategy {

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final PolicyNumberNormalizer policyNormalizer;

    public QuetzalPaymentStrategy(
            ObjectMapper objectMapper, Validator validator, PolicyNumberNormalizer policyNormalizer) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.policyNormalizer = policyNormalizer;
    }

    @Override
    public String providerKey() {
        return "quetzal";
    }

    @Override
    public CanonicalPaymentEvent mapToCanonical(String rawBody, String correlationId) throws Exception {
        QuetzalWebhookRequest payload = objectMapper.readValue(rawBody, QuetzalWebhookRequest.class);
        validatePayload(payload);

        var charge = payload.data().charge();
        String currency = charge.currency().trim().toUpperCase();
        String policyNumber = policyNormalizer.normalize(charge.reference(), "MX");

        String amount = BigDecimal.valueOf(charge.amount())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                .toPlainString();

        String canonicalStatus =
                switch (payload.type().trim()) {
                    case "charge.succeeded" -> "CAPTURED";
                    case "charge.failed" -> "FAILED";
                    case "charge.refunded" -> "REFUNDED";
                    default -> throw new IllegalArgumentException("Unsupported Quetzal event type: " + payload.type());
                };

        long statusEpoch = payload.created();
        if ("REFUNDED".equals(canonicalStatus) && charge.refundedAt() != null) {
            statusEpoch = charge.refundedAt();
        } else if ("CAPTURED".equals(canonicalStatus) && charge.paidAt() != null) {
            statusEpoch = charge.paidAt();
        }

        return new CanonicalPaymentEvent(
                CanonicalPaymentEvent.SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                correlationId,
                "QUETZAL",
                "MX",
                "QUETZAL:" + charge.id().trim(),
                payload.id().trim(),
                charge.id().trim(),
                "QUETZAL:" + payload.id().trim(),
                policyNumber,
                canonicalStatus,
                amount,
                currency,
                Instant.ofEpochSecond(statusEpoch),
                Instant.now());
    }

    private <T> void validatePayload(T target) {
        Set<ConstraintViolation<T>> violations = validator.validate(target);
        if (!violations.isEmpty()) {
            ConstraintViolation<T> first = violations.iterator().next();
            throw new IllegalArgumentException(first.getPropertyPath() + " " + first.getMessage());
        }
    }
}
