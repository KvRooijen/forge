package forge.headless.server.ai;

import forge.headless.protocol.DecisionRequest;

import java.util.ArrayList;
import java.util.List;

/** Same "take the minimum required, no more" rule the simple heuristic AI
 * used - safe and predictable, but doesn't yet distinguish e.g. discarding
 * the worst card vs. the best one. Not elaborated yet. */
public class GenericListChoiceStrategy implements ListChoiceStrategy {
    @Override
    public List<String> chooseFromList(List<DecisionRequest.Option> options, Integer min) {
        int minN = min != null ? min : 0;
        List<String> chosen = new ArrayList<>();
        for (int i = 0; i < Math.min(minN, options.size()); i++) {
            chosen.add(options.get(i).id);
        }
        return chosen;
    }
}
