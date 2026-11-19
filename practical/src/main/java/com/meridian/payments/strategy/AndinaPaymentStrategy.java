package com.meridian.payments.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.payments.model.CanonicalPaymentEvent;
import com.meridian.payments.service.PolicyNumberNormalizer;

/**
 * Strategy implementation for Andina Pay (Colombia).
 */
@Component
public class AndinaPaymentStrategy implements PaymentProviderStrategy {

    private final ObjectMapper objectMapper;
    private final PolicyNumberNormalizer policyNormalizer;

    public AndinaPaymentStrategy(ObjectMapper objectMapper, PolicyNumberNormalizer policyNormalizer) {
        this.objectMapper = objectMapper;
        this.policyNormalizer = policyNormalizer;
    }

    @Override
    public String providerKey() {
        return "andina";
    }

    @Override
    public CanonicalPaymentEvent mapToCanonical(String rawBody, String correlationId) throws Exception {
        // 1. Parse raw JSON payload from Andina
        JsonNode root = objectMapper.readTree(rawBody);
        String notificationId = root.path("notificacion_id").asText();

        JsonNode tx = root.path("transaccion");
        String txId = tx.path("id").asText();
        String rawState = tx.path("estado").asText().trim().toUpperCase();
        String amount = new BigDecimal(tx.path("valor").asText())
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
        String currency = tx.path("moneda").asText("COP").trim().toUpperCase();
        String policyNumber = policyNormalizer.normalize(tx.path("referencia").asText(), "CO");
        Instant statusChangedAt = OffsetDateTime.parse(tx.path("fecha_transaccion").asText()).toInstant();

        // 2. Map Spanish transaction state to canonical payment status
        String canonicalStatus = switch (rawState) {
            case "PENDIENTE" -> "PENDING";
            case "APROBADA" -> "CAPTURED";
            case "RECHAZADA" -> "FAILED";
            case "REVERSADA" -> "REFUNDED";
            default -> "PENDING";
        };

        // 3. Build canonical event (idempotency uses txId + state because notificacion_id changes on retries)
        return new CanonicalPaymentEvent(
                CanonicalPaymentEvent.SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                correlationId,
                "ANDINA",
                "CO",
                "ANDINA:" + txId,
                notificationId,
                txId,
                "ANDINA:" + txId + ":" + rawState,
                policyNumber,
                canonicalStatus,
                amount,
                currency,
                statusChangedAt,
                Instant.now());
    }
}
