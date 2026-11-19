package com.meridian.payments.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.payments.model.CanonicalPaymentEvent;

/**
 * Initial happy-path intake service for Andina Pay (CO) and Quetzal Pagos (MX).
 * Parses provider payloads, maps them to the canonical schema, and strips payer PII.
 */
@Service
public class WebhookIntakeService {

    private static final Logger log = LoggerFactory.getLogger(WebhookIntakeService.class);

    private final ObjectMapper objectMapper;
    private final List<CanonicalPaymentEvent> publishedEvents = Collections.synchronizedList(new ArrayList<>());

    public WebhookIntakeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CanonicalPaymentEvent processAndina(String rawBody, String correlationId) throws Exception {
        // 1. Parse raw JSON payload from Andina
        JsonNode root = objectMapper.readTree(rawBody);
        String notificationId = root.path("notificacion_id").asText();

        JsonNode tx = root.path("transaccion");
        String txId = tx.path("id").asText();
        String rawState = tx.path("estado").asText();
        String amount = new BigDecimal(tx.path("valor").asText())
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
        String currency = tx.path("moneda").asText();
        String reference = tx.path("referencia").asText();
        Instant statusChangedAt = OffsetDateTime.parse(tx.path("fecha_transaccion").asText()).toInstant();

        // 2. Map Spanish transaction state to canonical payment status
        String canonicalStatus = switch (rawState) {
            case "PENDIENTE" -> "PENDING";
            case "APROBADA" -> "CAPTURED";
            case "RECHAZADA" -> "FAILED";
            case "REVERSADA" -> "REFUNDED";
            default -> "PENDING";
        };

        // 3. Build canonical event without payer PII
        CanonicalPaymentEvent event = new CanonicalPaymentEvent(
                CanonicalPaymentEvent.SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                correlationId,
                "ANDINA",
                "CO",
                "ANDINA:" + txId,
                notificationId,
                txId,
                "ANDINA:" + txId + ":" + rawState,
                reference,
                canonicalStatus,
                amount,
                currency,
                statusChangedAt,
                Instant.now());

        // 4. Store in memory stub
        publishedEvents.add(event);
        log.info("Accepted Andina webhook: paymentKey={}, status={}, correlationId={}",
                event.paymentKey(), event.status(), correlationId);

        return event;
    }

    public CanonicalPaymentEvent processQuetzal(String rawBody, String correlationId) throws Exception {
        // 1. Parse raw JSON payload from Quetzal
        JsonNode root = objectMapper.readTree(rawBody);
        String providerEventId = root.path("id").asText();
        String eventType = root.path("type").asText();
        long createdEpoch = root.path("created").asLong();

        JsonNode charge = root.path("data").path("charge");
        String chargeId = charge.path("id").asText();
        long amountMinor = charge.path("amount").asLong();
        String currency = charge.path("currency").asText("MXN").toUpperCase();
        String reference = charge.path("reference").asText();

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

        // 4. Resolve authoritative status timestamp (paid_at, refunded_at, or fallback to created)
        long statusEpoch = createdEpoch;
        if ("REFUNDED".equals(canonicalStatus) && charge.hasNonNull("refunded_at")) {
            statusEpoch = charge.path("refunded_at").asLong();
        } else if ("CAPTURED".equals(canonicalStatus) && charge.hasNonNull("paid_at")) {
            statusEpoch = charge.path("paid_at").asLong();
        }

        // 5. Build canonical event without customer email PII
        CanonicalPaymentEvent event = new CanonicalPaymentEvent(
                CanonicalPaymentEvent.SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                correlationId,
                "QUETZAL",
                "MX",
                "QUETZAL:" + chargeId,
                providerEventId,
                chargeId,
                "QUETZAL:" + providerEventId,
                reference,
                canonicalStatus,
                amount,
                currency,
                Instant.ofEpochSecond(statusEpoch),
                Instant.now());

        // 6. Store in memory stub
        publishedEvents.add(event);
        log.info("Accepted Quetzal webhook: paymentKey={}, status={}, correlationId={}",
                event.paymentKey(), event.status(), correlationId);

        return event;
    }

    public List<CanonicalPaymentEvent> getPublishedEvents() {
        return List.copyOf(publishedEvents);
    }
}
