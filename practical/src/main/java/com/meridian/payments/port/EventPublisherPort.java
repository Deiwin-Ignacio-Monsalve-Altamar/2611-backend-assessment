package com.meridian.payments.port;

import com.meridian.payments.model.CanonicalPaymentEvent;
import java.util.List;

/**
 * Driven port for publishing validated canonical payment events downstream
 * (backed by Azure Service Bus Topic in production).
 */
public interface EventPublisherPort {

    void publish(CanonicalPaymentEvent event);

    List<CanonicalPaymentEvent> publishedEvents();
}
