package com.meridian.payments.port;

import com.meridian.payments.model.CanonicalPaymentEvent;
import java.util.Optional;

/**
 * Driven port for payment persistence: atomic idempotency registration
 * and tracking the latest known state per paymentKey (backed by Cosmos DB in production).
 */
public interface PaymentStorePort {

    Optional<CanonicalPaymentEvent> registerIfNew(CanonicalPaymentEvent event);

    Optional<CanonicalPaymentEvent> findLatestByPaymentKey(String paymentKey);

    void saveLatest(CanonicalPaymentEvent event);
}
