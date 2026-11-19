package com.meridian.payments.model;

import java.time.Instant;
import java.util.Set;

/**
 * State pattern governing valid payment status transitions.
 *
 * Payment gateways deliver webhooks at-least-once without ordering guarantees.
 * Each state encapsulates its allowed transitions so out-of-order notifications
 * cannot regress a payment (e.g., a delayed CAPTURED event overwriting REFUNDED).
 */
public sealed interface PaymentLifecycleState
        permits PaymentLifecycleState.Initial,
                PaymentLifecycleState.Pending,
                PaymentLifecycleState.Captured,
                PaymentLifecycleState.Failed,
                PaymentLifecycleState.Refunded {

    String status();

    static PaymentLifecycleState from(String currentStatus) {
        if (currentStatus == null || currentStatus.isBlank()) {
            return new Initial();
        }
        return switch (currentStatus.trim().toUpperCase()) {
            case "PENDING" -> new Pending();
            case "CAPTURED" -> new Captured();
            case "FAILED" -> new Failed();
            case "REFUNDED" -> new Refunded();
            default -> new Initial();
        };
    }

    boolean canTransitionTo(String nextStatus, Instant currentChangedAt, Instant incomingChangedAt);

    record Initial() implements PaymentLifecycleState {
        @Override
        public String status() {
            return null;
        }

        @Override
        public boolean canTransitionTo(String nextStatus, Instant currentChangedAt, Instant incomingChangedAt) {
            return nextStatus != null && !nextStatus.isBlank();
        }
    }

    record Pending() implements PaymentLifecycleState {
        private static final Set<String> ALLOWED_NEXT = Set.of("CAPTURED", "FAILED", "REFUNDED");

        @Override
        public String status() {
            return "PENDING";
        }

        @Override
        public boolean canTransitionTo(String nextStatus, Instant currentChangedAt, Instant incomingChangedAt) {
            return ALLOWED_NEXT.contains(nextStatus);
        }
    }

    record Captured() implements PaymentLifecycleState {
        @Override
        public String status() {
            return "CAPTURED";
        }

        @Override
        public boolean canTransitionTo(String nextStatus, Instant currentChangedAt, Instant incomingChangedAt) {
            // Once captured, only a refund/reversal can move the lifecycle forward
            return "REFUNDED".equals(nextStatus);
        }
    }

    record Failed() implements PaymentLifecycleState {
        @Override
        public String status() {
            return "FAILED";
        }

        @Override
        public boolean canTransitionTo(String nextStatus, Instant currentChangedAt, Instant incomingChangedAt) {
            // A failed payment can only transition to CAPTURED if a strictly newer retry succeeded
            if (!"CAPTURED".equals(nextStatus)) {
                return false;
            }
            return currentChangedAt == null || incomingChangedAt.isAfter(currentChangedAt);
        }
    }

    record Refunded() implements PaymentLifecycleState {
        @Override
        public String status() {
            return "REFUNDED";
        }

        @Override
        public boolean canTransitionTo(String nextStatus, Instant currentChangedAt, Instant incomingChangedAt) {
            // Terminal state: never regress to Captured, Pending, or Failed
            return false;
        }
    }
}
