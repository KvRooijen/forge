package forge.headless.server.ai;

import forge.headless.protocol.DecisionRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** "Take the minimum required" for most CHOOSE_LIST prompts (surveil,
 * mode choice, search-keep, ...) - safe and predictable, but for
 * listIntent="WORST" (discard/sacrifice/destroy: this player is about to
 * lose whichever items are picked), choosing the *least* valuable
 * options actually matters - taking the first N arbitrarily could throw
 * away the best card in hand while keeping a strictly worse one. */
public class GenericListChoiceStrategy implements ListChoiceStrategy {
    @Override
    public List<String> chooseFromList(List<DecisionRequest.Option> options, Integer min, String listIntent) {
        int minN = min != null ? min : 0;
        if (minN <= 0) {
            return List.of();
        }
        if ("WORST".equals(listIntent)) {
            List<DecisionRequest.Option> byValueAsc = new ArrayList<>(options);
            byValueAsc.sort(Comparator.comparingDouble(o -> o.card != null ? CreatureValue.of(o.card) : 0));
            List<String> chosen = new ArrayList<>();
            for (int i = 0; i < Math.min(minN, byValueAsc.size()); i++) {
                chosen.add(byValueAsc.get(i).id);
            }
            return chosen;
        }
        List<String> chosen = new ArrayList<>();
        for (int i = 0; i < Math.min(minN, options.size()); i++) {
            chosen.add(options.get(i).id);
        }
        return chosen;
    }
}
