package com.meridian.payments.adapter.out.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import com.meridian.payments.model.CanonicalPaymentEvent;
import com.meridian.payments.port.EventPublisherPort;

/**
 * In-memory adapter replacing Azure Service Bus Topic publishing for local runs and tests.
 */
@Component
public class InMemoryEventPublisherAdapter implements EventPublisherPort {

    private final List<CanonicalPaymentEvent> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void publish(CanonicalPaymentEvent event) {
        events.add(event);
    }

    @Override
    public List<CanonicalPaymentEvent> publishedEvents() {
        return List.copyOf(events);
    }
}
