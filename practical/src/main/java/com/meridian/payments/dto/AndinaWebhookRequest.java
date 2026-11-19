package com.meridian.payments.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Strongly-typed incoming webhook contract for Andina Pay (Colombia).
 */
public record AndinaWebhookRequest(
        @NotBlank @JsonProperty("notificacion_id") String notificacionId,
        @NotNull @Valid @JsonProperty("transaccion") Transaccion transaccion) {

    public record Transaccion(
            @NotBlank String id,
            @NotBlank String estado,
            @NotNull @Positive BigDecimal valor,
            @NotBlank String moneda,
            @NotBlank String referencia,
            @NotNull @JsonProperty("fecha_transaccion") OffsetDateTime fechaTransaccion,
            @JsonProperty("motivo_reverso") String motivoReverso) {
    }
}
