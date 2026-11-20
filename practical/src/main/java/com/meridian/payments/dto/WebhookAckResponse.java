package com.meridian.payments.dto;

/**
 * Minimal acknowledgement returned to the payment provider.
 */
public record WebhookAckResponse(String status, String paymentKey, String correlationId) {}
