package com.meridian.payments.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.payments.dto.AndinaWebhookRequest;
import com.meridian.payments.model.CanonicalPaymentEvent;
import com.meridian.payments.service.PolicyNumberNormalizer;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Strategy implementation for Andina Pay (Colombia).
 */
@Component
public class AndinaPaymentStrategy implements PaymentProviderStrategy {

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final PolicyNumberNormalizer policyNormalizer;

    public AndinaPaymentStrategy(
            ObjectMapper objectMapper, Validator validator, PolicyNumberNormalizer policyNormalizer) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.policyNormalizer = policyNormalizer;
    }

    @Override
    public String providerKey() {
        return "andina";
    }

    @Override
    public CanonicalPaymentEvent mapToCanonical(String rawBody, String correlationId) throws Exception {
        AndinaWebhookRequest payload = objectMapper.readValue(rawBody, AndinaWebhookRequest.class);
        validatePayload(payload);

        var tx = payload.transaction();
        String rawState = tx.status().trim().toUpperCase();
        String amount = tx.amount().setScale(2, RoundingMode.HALF_UP).toPlainString();
        String currency = tx.currency().trim().toUpperCase();
        String policyNumber = policyNormalizer.normalize(tx.reference(), "CO");

        String canonicalStatus =
                switch (rawState) {
                    case "PENDIENTE" -> "PENDING";
                    case "APROBADA" -> "CAPTURED";
                    case "RECHAZADA" -> "FAILED";
                    case "REVERSADA" -> "REFUNDED";
                    default -> throw new IllegalArgumentException("Unsupported Andina transaction state: " + rawState);
                };

        return new CanonicalPaymentEvent(
                CanonicalPaymentEvent.SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                correlationId,
                "ANDINA",
                "CO",
                "ANDINA:" + tx.id().trim(),
                payload.notificationId().trim(),
                tx.id().trim(),
                "ANDINA:" + tx.id().trim() + ":" + rawState,
                policyNumber,
                canonicalStatus,
                amount,
                currency,
                tx.transactionDate().toInstant(),
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
