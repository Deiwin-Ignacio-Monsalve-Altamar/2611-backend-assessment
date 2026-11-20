package com.meridian.payments.adapter.out.memory;

import com.meridian.payments.model.CanonicalPaymentEvent;
import com.meridian.payments.port.PaymentStorePort;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Thread-safe in-memory adapter for idempotency and payment state tracking.
 */
@Component
public class InMemoryPaymentStoreAdapter implements PaymentStorePort {

    private final Map<String, CanonicalPaymentEvent> eventsByIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<String, CanonicalPaymentEvent> latestByPaymentKey = new ConcurrentHashMap<>();

    @Override
    public Optional<CanonicalPaymentEvent> registerIfNew(CanonicalPaymentEvent event) {
        return Optional.ofNullable(eventsByIdempotencyKey.putIfAbsent(event.idempotencyKey(), event));
    }

    @Override
    public Optional<CanonicalPaymentEvent> findLatestByPaymentKey(String paymentKey) {
        return Optional.ofNullable(latestByPaymentKey.get(paymentKey));
    }

    @Override
    public void saveLatest(CanonicalPaymentEvent event) {
        latestByPaymentKey.put(event.paymentKey(), event);
    }
}
