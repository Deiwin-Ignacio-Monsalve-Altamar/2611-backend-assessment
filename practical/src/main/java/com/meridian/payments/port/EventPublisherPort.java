package com.meridian.payments.port;

import java.util.List;

import com.meridian.payments.model.CanonicalPaymentEvent;

/**
 * Driven port for publishing validated canonical payment events downstream
 * (backed by Azure Service Bus Topic in production).
 */
public interface EventPublisherPort {

    void publish(CanonicalPaymentEvent event);

    List<CanonicalPaymentEvent> publishedEvents();
}
