package com.meridian.payments.controller;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.meridian.payments.dto.WebhookAckResponse;
import com.meridian.payments.security.WebhookHmacVerifier;
import com.meridian.payments.service.IntakeResult;
import com.meridian.payments.service.WebhookIntakeService;

/**
 * HTTP entry point for payment provider webhooks.
 */
@RestController
@RequestMapping("/v1")
public class WebhookController {

    private final WebhookIntakeService intakeService;
    private final WebhookHmacVerifier hmacVerifier;

    public WebhookController(
            WebhookIntakeService intakeService,
            WebhookHmacVerifier hmacVerifier) {
        this.intakeService = intakeService;
        this.hmacVerifier = hmacVerifier;
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("payments-intake is running");
    }

    @PostMapping("/webhooks/{provider}")
    public ResponseEntity<WebhookAckResponse> receiveWebhook(
            @PathVariable String provider,
            @RequestBody String rawBody,
            @RequestHeader HttpHeaders headers) throws Exception {

        hmacVerifier.verify(provider, rawBody, headers);

        String headerCorrelation = headers.getFirst("X-Correlation-Id");
        String correlationId = (headerCorrelation != null && !headerCorrelation.isBlank())
                ? headerCorrelation.trim()
                : UUID.randomUUID().toString();

        IntakeResult result = intakeService.processWebhook(provider, rawBody, correlationId);
        HttpStatus status = switch (result.outcome()) {
            case ACCEPTED -> HttpStatus.ACCEPTED;
            case DUPLICATE, OUT_OF_ORDER_IGNORED -> HttpStatus.OK;
        };

        WebhookAckResponse body = new WebhookAckResponse(
                result.outcome().name(),
                result.event().paymentKey(),
                correlationId);

        return ResponseEntity.status(status).body(body);
    }
}
