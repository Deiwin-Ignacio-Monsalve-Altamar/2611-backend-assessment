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
 * Initial happy-path intake service starting with Andina Pay (Colombia).
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

        // 4. Store in memory stub (Service Bus publisher added in later step)
        publishedEvents.add(event);
        log.info("Accepted Andina webhook: paymentKey={}, status={}, correlationId={}",
                event.paymentKey(), event.status(), correlationId);

        return event;
    }

    public List<CanonicalPaymentEvent> getPublishedEvents() {
        return List.copyOf(publishedEvents);
    }
}
