package forge.headless.server.ai;

import forge.headless.protocol.DecisionRequest;

import java.util.List;

public interface ManaPaymentStrategy {
    /** attemptNumber is how many times in a row this exact remaining-cost
     * prompt has repeated (1 the first time) - tracking *which* prompt is
     * repeating is the caller's bookkeeping, not this strategy's; it only
     * decides what to do given the count, e.g. give up after N. */
    List<String> choosePayment(List<DecisionRequest.Option> options, int attemptNumber);
}
