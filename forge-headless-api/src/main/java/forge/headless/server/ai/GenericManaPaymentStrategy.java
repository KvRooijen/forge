package forge.headless.server.ai;

import forge.headless.protocol.DecisionRequest;

import java.util.List;

/** Same "always auto-pay, give up after 3 identical repeats" rule the
 * simple heuristic AI used - not elaborated yet (choosing *which* lands
 * to tap, preserving flexibility for what's still in hand, is a later
 * category). */
public class GenericManaPaymentStrategy implements ManaPaymentStrategy {
    @Override
    public List<String> choosePayment(List<DecisionRequest.Option> options, int attemptNumber) {
        if (attemptNumber >= 3) {
            for (DecisionRequest.Option o : options) {
                if (o.id.equals("__CANCEL__")) {
                    return List.of(o.id);
                }
            }
        }
        for (DecisionRequest.Option o : options) {
            if (o.id.equals("__AUTO__")) {
                return List.of(o.id);
            }
        }
        return List.of();
    }
}
