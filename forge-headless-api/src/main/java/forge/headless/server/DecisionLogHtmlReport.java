package forge.headless.server;

import forge.headless.server.DecisionLogStats.Aggregation;
import forge.headless.server.DecisionLogStats.DeckAiStats;
import forge.headless.server.DecisionLogStats.YouOnlyStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Renders a DecisionLogStats.Aggregation as a single self-contained HTML
 * file - numeric comparison tables (RULE_BASED_V2 vs FORGE_AI) with the
 * better side bolded per metric, switchable between a combined "Total"
 * view and one tab per deck. No external JS/CSS dependency beyond a
 * small inline tab-switcher, so it always renders the same regardless of
 * where it's opened.
 */
final class DecisionLogHtmlReport {
    private DecisionLogHtmlReport() { }

    /** Which side of a metric counts as "better", for bolding - higher
     * isn't automatically good (a longer game or a bigger hand isn't
     * obviously better or worse for either AI), so this is set per row,
     * not assumed. */
    private enum Dir { HIGHER, LOWER, NONE }

    static String render(Aggregation agg, int[] turnCheckpoints) {
        Map<String, Map<String, DeckAiStats>> byDeck = new TreeMap<>();
        for (var e : agg.byDeckAi.entrySet()) {
            String[] parts = e.getKey().split(" \\| ", 2);
            String deck = parts[0];
            String ai = parts.length > 1 ? parts[1] : "?";
            byDeck.computeIfAbsent(deck, k -> new TreeMap<>()).put(ai, e.getValue());
        }
        DeckAiStats totalYou = merge(byDeck.values().stream().map(m -> m.get("RULE_BASED_V2")).toList());
        DeckAiStats totalOpp = merge(byDeck.values().stream().map(m -> m.get("FORGE_AI")).toList());

        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset='utf-8'><title>RULE_BASED_V2 vs FORGE_AI</title>");
        sb.append("<style>").append(CSS).append("</style></head><body>");
        sb.append("<h1>RULE_BASED_V2 vs FORGE_AI</h1>");
        sb.append("<p class='legend'><b class='you'>RULE_BASED_V2</b> &nbsp; <b class='opp'>FORGE_AI</b> &nbsp; ")
                .append("<span class='hint'>bold = better on that metric (n/a metrics left unmarked)</span></p>");

        List<String> tabIds = new ArrayList<>();
        List<String> tabLabels = new ArrayList<>();
        tabIds.add("total");
        tabLabels.add("Total");
        for (String deck : byDeck.keySet()) {
            tabIds.add("deck_" + tabIds.size());
            tabLabels.add(deck);
        }

        sb.append("<div class='tabs'>");
        for (int i = 0; i < tabIds.size(); i++) {
            sb.append("<button class='tabbtn").append(i == 0 ? " active" : "").append("' data-tab='")
                    .append(tabIds.get(i)).append("'>").append(esc(tabLabels.get(i))).append("</button>");
        }
        sb.append("</div>");

        sb.append("<div id='total' class='tabpanel active'>");
        appendDeckSection(sb, totalYou, totalOpp, agg.youOnlyOverall, turnCheckpoints);
        sb.append("</div>");

        int idx = 0;
        for (var deckEntry : byDeck.entrySet()) {
            String deck = deckEntry.getKey();
            DeckAiStats you = deckEntry.getValue().get("RULE_BASED_V2");
            DeckAiStats opp = deckEntry.getValue().get("FORGE_AI");
            YouOnlyStats yo = agg.youOnlyByDeck.get(deck);
            sb.append("<div id='deck_").append(idx + 1).append("' class='tabpanel'>");
            appendDeckSection(sb, you, opp, yo, turnCheckpoints);
            sb.append("</div>");
            idx++;
        }

        sb.append("<script>").append(JS).append("</script>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static void appendDeckSection(StringBuilder sb, DeckAiStats you, DeckAiStats opp, YouOnlyStats yo, int[] turnCheckpoints) {
        sb.append("<table class='cmp'><tr><th>Metric</th><th class='you'>RULE_BASED_V2</th><th class='opp'>FORGE_AI</th></tr>");
        row(sb, "Game length (turns)", g(you, s -> s.gameLengthSum / (double) s.games), g(opp, s -> s.gameLengthSum / (double) s.games), Dir.NONE);
        row(sb, "Commander cast %", g(you, s -> 100.0 * s.commanderCastGames / s.games), g(opp, s -> 100.0 * s.commanderCastGames / s.games), Dir.HIGHER);
        row(sb, "Commander cast turn (avg)",
                g(you, s -> s.commanderCastGames > 0 ? s.commanderCastTurnSum / (double) s.commanderCastGames : Double.NaN),
                g(opp, s -> s.commanderCastGames > 0 ? s.commanderCastTurnSum / (double) s.commanderCastGames : Double.NaN), Dir.LOWER);
        row(sb, "Commander by turn 5 %", g(you, s -> 100.0 * s.commanderByTurn5 / s.games), g(opp, s -> 100.0 * s.commanderByTurn5 / s.games), Dir.HIGHER);

        for (int ci = 0; ci < turnCheckpoints.length; ci++) {
            int t = turnCheckpoints[ci];
            sb.append("<tr class='section'><td colspan='3'>By turn ").append(t).append("</td></tr>");
            row(sb, "Lands", avgAt(you, ci, "lands"), avgAt(opp, ci, "lands"), Dir.HIGHER);
            row(sb, "Mana sources", avgAt(you, ci, "mana"), avgAt(opp, ci, "mana"), Dir.HIGHER);
            row(sb, "Creatures", avgAt(you, ci, "creatures"), avgAt(opp, ci, "creatures"), Dir.HIGHER);
            row(sb, "Life", avgAt(you, ci, "life"), avgAt(opp, ci, "life"), Dir.HIGHER);
            row(sb, "Hand size", avgAt(you, ci, "hand"), avgAt(opp, ci, "hand"), Dir.NONE);
        }

        sb.append("<tr class='section'><td colspan='3'>Spells cast per game (approx., via stack/board observation)</td></tr>");
        row(sb, "Removal", g(you, s -> s.removalCastViaStack / (double) s.games), g(opp, s -> s.removalCastViaStack / (double) s.games), Dir.HIGHER);
        row(sb, "Sweepers (board wipes)", g(you, s -> s.sweeperCastViaStack / (double) s.games), g(opp, s -> s.sweeperCastViaStack / (double) s.games), Dir.HIGHER);
        row(sb, "Ramp spells", g(you, s -> s.rampSpellCast / (double) s.games), g(opp, s -> s.rampSpellCast / (double) s.games), Dir.HIGHER);
        row(sb, "Mana rocks/dorks", g(you, s -> s.manaRocks / (double) s.games), g(opp, s -> s.manaRocks / (double) s.games), Dir.HIGHER);
        sb.append("</table>");

        if (yo != null) {
            sb.append("<h3>RULE_BASED_V2-only (FORGE_AI's own decisions aren't logged)</h3>");
            sb.append("<table class='cmp single'><tr><th>Metric</th><th class='you'>RULE_BASED_V2</th></tr>");
            rowSingle(sb, "Removal cast/game (precise)", yo.removalCastSum / (double) yo.games);
            rowSingle(sb, "Removal cast/game (stack cross-check)", yo.removalCastViaStackSum / (double) yo.games);
            rowSingle(sb, "Sweeper cast/game (precise)", yo.sweeperCastSum / (double) yo.games);
            sb.append("</table>");
            sb.append("<table class='mulligan'><tr><th>Mulligans taken</th><th>Win rate</th><th>n</th></tr>");
            for (var m : yo.winsByMulligan.entrySet()) {
                int wins = m.getValue()[0];
                int total = m.getValue()[1];
                sb.append("<tr><td>").append(m.getKey()).append("</td><td>")
                        .append(String.format("%.1f%%", 100.0 * wins / total)).append("</td><td>")
                        .append(wins).append("/").append(total).append("</td></tr>");
            }
            sb.append("</table>");
        }
    }

    private static double g(DeckAiStats s, java.util.function.ToDoubleFunction<DeckAiStats> f) {
        return s == null || s.games == 0 ? Double.NaN : f.applyAsDouble(s);
    }

    private static double avgAt(DeckAiStats s, int ci, String which) {
        if (s == null) {
            return Double.NaN;
        }
        long sum;
        int count;
        switch (which) {
            case "lands": sum = s.landsAt[ci]; count = s.landsAtCount[ci]; break;
            case "mana": sum = s.manaSourcesAt[ci]; count = s.manaSourcesAtCount[ci]; break;
            case "creatures": sum = s.creaturesAt[ci]; count = s.creaturesAtCount[ci]; break;
            case "life": sum = s.lifeAt[ci]; count = s.lifeAtCount[ci]; break;
            case "hand": sum = s.handSizeAt[ci]; count = s.handSizeAtCount[ci]; break;
            default: return Double.NaN;
        }
        return count == 0 ? Double.NaN : sum / (double) count;
    }

    /** Sums every accumulator field across a deck's per-AI stats from
     * multiple decks into one combined total - same shape as a single
     * DeckAiStats, just aggregated further for the "Total" tab. */
    private static DeckAiStats merge(List<DeckAiStats> list) {
        DeckAiStats m = new DeckAiStats();
        for (DeckAiStats s : list) {
            if (s == null) {
                continue;
            }
            m.games += s.games;
            m.gameLengthSum += s.gameLengthSum;
            m.commanderCastGames += s.commanderCastGames;
            m.commanderCastTurnSum += s.commanderCastTurnSum;
            m.commanderByTurn5 += s.commanderByTurn5;
            m.removalCastViaStack += s.removalCastViaStack;
            m.sweeperCastViaStack += s.sweeperCastViaStack;
            m.rampSpellCast += s.rampSpellCast;
            m.manaRocks += s.manaRocks;
            for (int ci = 0; ci < s.landsAt.length; ci++) {
                m.landsAt[ci] += s.landsAt[ci];
                m.landsAtCount[ci] += s.landsAtCount[ci];
                m.creaturesAt[ci] += s.creaturesAt[ci];
                m.creaturesAtCount[ci] += s.creaturesAtCount[ci];
                m.manaSourcesAt[ci] += s.manaSourcesAt[ci];
                m.manaSourcesAtCount[ci] += s.manaSourcesAtCount[ci];
                m.handSizeAt[ci] += s.handSizeAt[ci];
                m.handSizeAtCount[ci] += s.handSizeAtCount[ci];
                m.lifeAt[ci] += s.lifeAt[ci];
                m.lifeAtCount[ci] += s.lifeAtCount[ci];
            }
        }
        return m;
    }

    private static void row(StringBuilder sb, String label, double youVal, double oppVal, Dir dir) {
        boolean youBetter = false;
        boolean oppBetter = false;
        if (dir != Dir.NONE && !Double.isNaN(youVal) && !Double.isNaN(oppVal) && youVal != oppVal) {
            boolean youHigher = youVal > oppVal;
            youBetter = dir == Dir.HIGHER ? youHigher : !youHigher;
            oppBetter = !youBetter;
        }
        sb.append("<tr><td>").append(esc(label)).append("</td>")
                .append("<td class='you").append(youBetter ? " better" : "").append("'>").append(fmt(youVal)).append("</td>")
                .append("<td class='opp").append(oppBetter ? " better" : "").append("'>").append(fmt(oppVal)).append("</td></tr>");
    }

    private static void rowSingle(StringBuilder sb, String label, double val) {
        sb.append("<tr><td>").append(esc(label)).append("</td><td class='you'>").append(fmt(val)).append("</td></tr>");
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "n/a" : String.format("%.2f", v);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final String CSS = """
        body { font-family: -apple-system, Segoe UI, Helvetica, Arial, sans-serif; margin: 24px; color: #1f2937; }
        h1 { margin-bottom: 4px; }
        h3 { margin-top: 22px; margin-bottom: 6px; color: #4b5563; font-size: 0.95em; text-transform: uppercase; letter-spacing: 0.03em; }
        .legend { color: #4b5563; }
        .legend .you, b.you { color: #2563eb; }
        .legend .opp, b.opp { color: #ea580c; }
        .hint { color: #9ca3af; font-size: 0.85em; }
        .tabs { margin: 16px 0; display: flex; gap: 6px; }
        .tabbtn { padding: 6px 14px; border: 1px solid #d1d5db; background: #f9fafb; border-radius: 6px; cursor: pointer; font-size: 0.9em; }
        .tabbtn.active { background: #1f2937; color: white; border-color: #1f2937; }
        .tabpanel { display: none; }
        .tabpanel.active { display: block; }
        table.cmp { border-collapse: collapse; width: 100%; max-width: 640px; margin-bottom: 8px; }
        table.cmp th, table.cmp td { text-align: left; padding: 5px 14px; font-size: 0.92em; border-bottom: 1px solid #f1f5f9; }
        table.cmp th.you { color: #2563eb; } table.cmp th.opp { color: #ea580c; }
        table.cmp td.you.better { color: #2563eb; font-weight: 700; }
        table.cmp td.opp.better { color: #ea580c; font-weight: 700; }
        tr.section td { padding-top: 14px; font-weight: 600; color: #6b7280; font-size: 0.8em; text-transform: uppercase; letter-spacing: 0.03em; border-bottom: none; }
        table.mulligan { border-collapse: collapse; margin: 6px 0 14px; }
        table.mulligan th, table.mulligan td { text-align: left; padding: 4px 14px; font-size: 0.9em; }
        table.mulligan th { color: #6b7280; font-weight: 600; }
        """;

    private static final String JS = """
        document.querySelectorAll('.tabbtn').forEach(btn => {
            btn.addEventListener('click', () => {
                document.querySelectorAll('.tabbtn').forEach(b => b.classList.remove('active'));
                document.querySelectorAll('.tabpanel').forEach(p => p.classList.remove('active'));
                btn.classList.add('active');
                document.getElementById(btn.dataset.tab).classList.add('active');
            });
        });
        """;
}
