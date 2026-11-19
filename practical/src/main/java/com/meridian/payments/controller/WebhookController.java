package com.meridian.payments.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.meridian.payments.model.CanonicalPaymentEvent;
import com.meridian.payments.service.IntakeResult;
import com.meridian.payments.service.WebhookIntakeService;

/**
 * HTTP entry point for payment provider webhooks.
 */
@RestController
@RequestMapping("/v1")
public class WebhookController {

    private final WebhookIntakeService intakeService;

    public WebhookController(WebhookIntakeService intakeService) {
        this.intakeService = intakeService;
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("payments-intake is running");
    }

    @PostMapping("/webhooks/{provider}")
    public ResponseEntity<CanonicalPaymentEvent> receiveWebhook(
            @PathVariable String provider,
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) throws Exception {

        String traceId = (correlationId != null && !correlationId.isBlank())
                ? correlationId
                : UUID.randomUUID().toString();

        try {
            IntakeResult result = intakeService.processWebhook(provider, rawBody, traceId);
            HttpStatus status = switch (result.outcome()) {
                case ACCEPTED -> HttpStatus.ACCEPTED;
                case DUPLICATE, OUT_OF_ORDER_IGNORED -> HttpStatus.OK;
            };
            return ResponseEntity.status(status).body(result.event());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
