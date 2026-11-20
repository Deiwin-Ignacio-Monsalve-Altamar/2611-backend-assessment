package com.meridian.payments.service;

import com.meridian.payments.model.CanonicalPaymentEvent;

/**
 * Outcome of processing an inbound webhook through idempotency and state checks.
 */
public record IntakeResult(Outcome outcome, CanonicalPaymentEvent event) {

    public enum Outcome {
        ACCEPTED,
        DUPLICATE,
        OUT_OF_ORDER_IGNORED
    }

    public static IntakeResult accepted(CanonicalPaymentEvent event) {
        return new IntakeResult(Outcome.ACCEPTED, event);
    }

    public static IntakeResult duplicate(CanonicalPaymentEvent existingEvent) {
        return new IntakeResult(Outcome.DUPLICATE, existingEvent);
    }

    public static IntakeResult outOfOrderIgnored(CanonicalPaymentEvent currentEvent) {
        return new IntakeResult(Outcome.OUT_OF_ORDER_IGNORED, currentEvent);
    }
}
