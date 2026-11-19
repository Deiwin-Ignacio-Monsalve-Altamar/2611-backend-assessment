package com.meridian.payments.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.meridian.payments.model.CanonicalPaymentEvent;
import com.meridian.payments.model.PaymentLifecycleState;
import com.meridian.payments.port.EventPublisherPort;
import com.meridian.payments.port.PaymentStorePort;
import com.meridian.payments.strategy.PaymentProviderStrategy;

/**
 * Orchestrates webhook intake using Strategy for provider mapping,
 * PaymentStorePort for idempotency/state, and EventPublisherPort for downstream publishing.
 */
@Service
public class WebhookIntakeService {

    private final Map<String, PaymentProviderStrategy> strategiesByProvider;
    private final PaymentStorePort paymentStore;
    private final EventPublisherPort eventPublisher;

    public WebhookIntakeService(
            List<PaymentProviderStrategy> strategies,
            PaymentStorePort paymentStore,
            EventPublisherPort eventPublisher) {
        this.strategiesByProvider = strategies.stream()
                .collect(Collectors.toUnmodifiableMap(
                        s -> s.providerKey().toLowerCase(),
                        Function.identity()));
        this.paymentStore = paymentStore;
        this.eventPublisher = eventPublisher;
    }

    public IntakeResult processWebhook(String providerKey, String rawBody, String correlationId) throws Exception {
        // 1. Resolve provider strategy
        PaymentProviderStrategy strategy = strategiesByProvider.get(providerKey.toLowerCase());
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported payment provider: " + providerKey);
        }

        // 2. Map raw payload to canonical event
        CanonicalPaymentEvent incoming = strategy.mapToCanonical(rawBody, correlationId);

        // 3. Atomic idempotency check
        Optional<CanonicalPaymentEvent> existingDuplicate = paymentStore.registerIfNew(incoming);
        if (existingDuplicate.isPresent()) {
            return IntakeResult.duplicate(existingDuplicate.get());
        }

        // 4. State machine check to prevent out-of-order regressions
        Optional<CanonicalPaymentEvent> currentPayment = paymentStore.findLatestByPaymentKey(incoming.paymentKey());
        PaymentLifecycleState currentState = PaymentLifecycleState.from(
                currentPayment.map(CanonicalPaymentEvent::status).orElse(null));

        boolean validTransition = currentState.canTransitionTo(
                incoming.status(),
                currentPayment.map(CanonicalPaymentEvent::statusChangedAt).orElse(null),
                incoming.statusChangedAt());

        if (!validTransition) {
            return IntakeResult.outOfOrderIgnored(currentPayment.orElse(incoming));
        }

        // 5. Save latest state and publish canonical event downstream
        paymentStore.saveLatest(incoming);
        eventPublisher.publish(incoming);

        return IntakeResult.accepted(incoming);
    }

    public List<CanonicalPaymentEvent> getPublishedEvents() {
        return eventPublisher.publishedEvents();
    }
}
