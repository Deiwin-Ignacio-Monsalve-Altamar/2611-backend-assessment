package com.meridian.payments.strategy;

import com.meridian.payments.model.CanonicalPaymentEvent;

/**
 * Strategy interface isolating provider-specific webhook parsing and mapping logic (NFR9).
 * Adding a new country/provider requires only implementing this contract without
 * modifying the core intake orchestrator.
 */
public interface PaymentProviderStrategy {

    /**
     * Provider route identifier (e.g., "andina", "quetzal").
     */
    String providerKey();

    /**
     * Parses a raw provider payload and translates it into Meridian's canonical event.
     */
    CanonicalPaymentEvent mapToCanonical(String rawBody, String correlationId) throws Exception;
}
