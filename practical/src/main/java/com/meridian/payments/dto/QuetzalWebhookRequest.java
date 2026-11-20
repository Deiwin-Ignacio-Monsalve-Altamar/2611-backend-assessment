package com.meridian.payments.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Strongly-typed incoming webhook contract for Quetzal Pagos (Mexico).
 */
public record QuetzalWebhookRequest(
        @NotBlank String id, @NotBlank String type, @NotNull @Positive Long created, @NotNull @Valid Data data) {

    public record Data(@NotNull @Valid Charge charge) {}

    public record Charge(
            @NotBlank String id,
            @NotNull @Positive Long amount,
            @NotBlank String currency,
            @NotBlank String reference,
            @JsonProperty("paid_at") Long paidAt,
            @JsonProperty("refunded_at") Long refundedAt,
            @JsonProperty("failure_code") String failureCode) {}
}
