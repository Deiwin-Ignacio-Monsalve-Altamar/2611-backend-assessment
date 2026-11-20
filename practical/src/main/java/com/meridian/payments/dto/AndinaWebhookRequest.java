package com.meridian.payments.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record AndinaWebhookRequest(
        @NotBlank @JsonProperty("notificacion_id") String notificationId,
        @NotNull @Valid @JsonProperty("transaccion") Transaction transaction) {

    public record Transaction(
            @NotBlank @JsonProperty("id") String id,
            @NotBlank @JsonProperty("estado") String status,
            @NotNull @Positive @JsonProperty("valor") BigDecimal amount,
            @NotBlank @JsonProperty("moneda") String currency,
            @NotBlank @JsonProperty("referencia") String reference,
            @NotNull @JsonProperty("fecha_transaccion") OffsetDateTime transactionDate,
            @JsonProperty("motivo_reverso") String reversalReason) {}
}
