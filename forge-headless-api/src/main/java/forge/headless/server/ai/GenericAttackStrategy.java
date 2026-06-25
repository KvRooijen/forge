package forge.headless.server.ai;

import forge.headless.protocol.CardStateView;
import forge.headless.protocol.DecisionRequest;
import forge.headless.protocol.GameStateView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Same "attack with anything that has power > 0" rule the simple
 * heuristic AI used - not elaborated yet (lethal-line detection, racing
 * math, and attacking into open mana/known-empty hands are a later
 * category). */
public class GenericAttackStrategy implements AttackStrategy {
    @Override
    public List<String> chooseAttackers(List<DecisionRequest.Option> options, GameStateView state) {
        Map<String, CardStateView> byId = AiUtils.cardsById(state);
        List<String> chosen = new ArrayList<>();
        for (DecisionRequest.Option o : options) {
            CardStateView card = o.cardId != null ? byId.get(o.cardId) : null;
            // Skip 0-power attackers - they can't profitably do anything
            // by attacking and just walk into a bad block for free.
            if (card == null || (card.power != null ? card.power : 0) > 0) {
                chosen.add(o.id);
            }
        }
        return chosen;
    }
}
