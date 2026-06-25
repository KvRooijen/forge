package forge.headless.server.ai;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared mana-cost-string parsing used by several strategies - pulled out
 * once both InProcessAiChannel and the new rule-based strategies needed
 * the exact same regex, rather than letting two copies drift apart. */
public final class ManaUtils {
    private static final Pattern MANA_SYMBOL = Pattern.compile("\\{([^}]+)\\}");

    private ManaUtils() { }

    public static int manaValue(String manaCost) {
        if (manaCost == null || manaCost.isEmpty()) {
            return 0;
        }
        int total = 0;
        Matcher m = MANA_SYMBOL.matcher(manaCost);
        while (m.find()) {
            String symbol = m.group(1);
            if (symbol.chars().allMatch(Character::isDigit) && !symbol.isEmpty()) {
                total += Integer.parseInt(symbol);
            } else if (!symbol.equals("X") && !symbol.equals("Y") && !symbol.equals("Z")) {
                total += 1;
            }
        }
        return total;
    }

    public static Set<String> colorsInCost(String manaCost) {
        Set<String> colors = new HashSet<>();
        if (manaCost == null) {
            return colors;
        }
        Matcher m = MANA_SYMBOL.matcher(manaCost);
        while (m.find()) {
            String symbol = m.group(1);
            if (symbol.length() == 1 && "WUBRG".contains(symbol)) {
                colors.add(symbol);
            }
        }
        return colors;
    }
}
