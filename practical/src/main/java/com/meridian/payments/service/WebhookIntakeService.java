package com.meridian.payments.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.meridian.payments.model.CanonicalPaymentEvent;
import com.meridian.payments.strategy.PaymentProviderStrategy;

/**
 * Orchestrates webhook intake using the Strategy pattern to isolate provider rules.
 */
@Service
public class WebhookIntakeService {

    private static final Logger log = LoggerFactory.getLogger(WebhookIntakeService.class);

    private final Map<String, PaymentProviderStrategy> strategiesByProvider;
    private final List<CanonicalPaymentEvent> publishedEvents = Collections.synchronizedList(new ArrayList<>());

    public WebhookIntakeService(List<PaymentProviderStrategy> strategies) {
        this.strategiesByProvider = strategies.stream()
                .collect(Collectors.toUnmodifiableMap(
                        s -> s.providerKey().toLowerCase(),
                        Function.identity()));
    }

    public CanonicalPaymentEvent processWebhook(String providerKey, String rawBody, String correlationId) throws Exception {
        // 1. Resolve provider strategy dynamically
        PaymentProviderStrategy strategy = strategiesByProvider.get(providerKey.toLowerCase());
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported payment provider: " + providerKey);
        }

        // 2. Delegate provider-specific parsing and mapping to the chosen strategy
        CanonicalPaymentEvent event = strategy.mapToCanonical(rawBody, correlationId);

        // 3. Publish canonical event to downstream bus (in-memory stub)
        publishedEvents.add(event);
        log.info("Accepted {} webhook: paymentKey={}, status={}, correlationId={}",
                event.provider(), event.paymentKey(), event.status(), correlationId);

        return event;
    }

    public List<CanonicalPaymentEvent> getPublishedEvents() {
        return List.copyOf(publishedEvents);
    }
}
