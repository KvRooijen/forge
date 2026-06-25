package forge.headless.server.ai;

import forge.headless.protocol.DecisionRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Finally gives CreatureValue/ThreatAssessor-style scoring a real
 * consumer: for a HARMFUL effect (removal, sacrifice, theft, ...), point
 * it at the opponent's most valuable/threatening creature rather than
 * whichever candidate happened to come first; for a BENEFICIAL one
 * (protection, untap, ...), point it at my own best creature instead.
 *
 * If the "right side" has no candidates at all (e.g. a harmful effect
 * that can only legally hit my own permanents), an optional pick (min=0)
 * declines rather than hurting myself for no reason; a mandatory one
 * (min=1, no choice but to pick *something*) targets the least valuable
 * candidate on the wrong side instead, to minimize the damage.
 */
public class GenericTargetingStrategy implements TargetingStrategy {
    @Override
    public List<String> chooseTarget(List<DecisionRequest.Option> options, String targetIntent, Integer min) {
        boolean mandatory = min != null && min > 0;
        boolean harmful = "HARMFUL".equals(targetIntent);

        List<DecisionRequest.Option> preferredSide = new ArrayList<>();
        List<DecisionRequest.Option> otherSide = new ArrayList<>();
        for (DecisionRequest.Option o : options) {
            boolean isMine = o.card != null && o.card.controllerIsYou;
            // HARMFUL wants an opponent's card (preferred = not mine);
            // BENEFICIAL wants my own (preferred = mine).
            boolean preferred = harmful ? !isMine : isMine;
            (preferred ? preferredSide : otherSide).add(o);
        }

        Comparator<DecisionRequest.Option> byValueDesc = Comparator.comparingDouble(
                (DecisionRequest.Option o) -> o.card != null ? CreatureValue.of(o.card) : 0).reversed();

        Optional<DecisionRequest.Option> best = preferredSide.stream().sorted(byValueDesc).findFirst();
        if (best.isPresent()) {
            return List.of(best.get().id);
        }
        if (!mandatory) {
            // No candidate on the side this effect should actually hit -
            // decline the optional pick rather than targeting my own
            // stuff with a harmful effect (or helping the opponent with
            // a beneficial one).
            return List.of();
        }
        // Forced to pick from the "wrong" side - take the least
        // damaging option available: lowest-value if harmful (minimize
        // what I lose), still highest-value if beneficial (an opponent's
        // creature getting buffed is bad either way, but at least pick
        // consistently rather than arbitrarily).
        Comparator<DecisionRequest.Option> tiebreak = harmful ? byValueDesc.reversed() : byValueDesc;
        Optional<DecisionRequest.Option> fallback = otherSide.stream().sorted(tiebreak).findFirst();
        return fallback.map(o -> List.of(o.id)).orElseGet(() -> options.isEmpty() ? List.of() : List.of(options.get(0).id));
    }
}
