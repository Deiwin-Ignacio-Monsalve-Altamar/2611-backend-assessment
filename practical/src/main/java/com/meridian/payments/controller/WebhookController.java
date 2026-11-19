package com.meridian.payments.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.meridian.payments.model.CanonicalPaymentEvent;
import com.meridian.payments.service.WebhookIntakeService;

/**
 * HTTP entry point for payment provider webhooks and basic health checks.
 */
@RestController
@RequestMapping("/v1")
public class WebhookController {

    private final WebhookIntakeService intakeService;

    // Constructor injection wired automatically by Spring Boot
    public WebhookController(WebhookIntakeService intakeService) {
        this.intakeService = intakeService;
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("payments-intake is running");
    }

    @PostMapping("/webhooks/andina")
    public ResponseEntity<CanonicalPaymentEvent> receiveAndina(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) throws Exception {

        String traceId = (correlationId != null && !correlationId.isBlank())
                ? correlationId
                : UUID.randomUUID().toString();

        CanonicalPaymentEvent event = intakeService.processAndina(rawBody, traceId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(event);
    }
}
