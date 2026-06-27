package forge.headless.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Offline tool, not part of the live decision path: aggregates board-state
 * statistics (lands/creatures/life/commander-cast-turn by a given turn,
 * etc.) across many logged games, broken down by deck and by which AI
 * piloted it - to find specific, concrete stats where one AI measurably
 * lags the other, rather than relying on aggregate win rate alone (which
 * this project has repeatedly found too noisy at affordable sample sizes
 * to localize anything).
 *
 * DecisionLogger only ever runs inside RuleBasedAiChannel, so only
 * RULE_BASED_V2 seats produce a log - but every record's GameStateView
 * already includes the *opponent's* public board (battlefield/life/hand
 * count - everything except their hidden hand) on every single decision,
 * since that's exactly what the real player legitimately saw. So a
 * RULE_BASED_V2-vs-FORGE_AI log already contains genuine, real board-state
 * stats for *both* sides - this tool just needs to read "you" and "opp"
 * out of the same records, no new logging required.
 *
 * Usage: java forge.headless.server.DecisionLogStats <out.txt> <in1.jsonl> [in2.jsonl ...]
 */
public final class DecisionLogStats {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int[] TURN_CHECKPOINTS = {3, 4, 5, 6, 8};

    private DecisionLogStats() { }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: DecisionLogStats <out.txt> <in1.jsonl> [in2.jsonl ...]");
            System.exit(1);
        }
        Map<String, GameTrack> games = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            try (var lines = Files.lines(Path.of(args[i]), StandardCharsets.UTF_8)) {
                lines.filter(l -> !l.isBlank()).forEach(line -> ingest(line, games));
            }
        }
        try (BufferedWriter out = Files.newBufferedWriter(Path.of(args[0]), StandardCharsets.UTF_8)) {
            out.write(report(games));
        }
        System.out.println("Wrote " + args[0] + " (" + games.size() + " games)");
    }

    private static void ingest(String line, Map<String, GameTrack> games) {
        JsonNode rec;
        try {
            rec = MAPPER.readTree(line);
        } catch (IOException e) {
            return;
        }
        String gameId = rec.path("seatChannelId").asText(null);
        if (gameId == null) {
            return;
        }
        GameTrack track = games.computeIfAbsent(gameId, k -> new GameTrack());
        JsonNode state = rec.path("request").path("state");
        int turn = state.path("turnNumber").asInt(-1);
        if (turn < 0) {
            return;
        }
        for (JsonNode p : state.path("players")) {
            boolean isYou = p.path("isYou").asBoolean();
            String name = p.path("name").asText("?");
            if (isYou) {
                track.youName = name;
            } else {
                track.oppName = name;
            }
            track.lastSnapshot.computeIfAbsent(turn, k -> new HashMap<>()).put(isYou, p);
            if (track.commanderTurn.get(isYou) == null) {
                for (JsonNode c : p.path("battlefield")) {
                    if (c.path("isCommander").asBoolean()) {
                        track.commanderTurn.put(isYou, turn);
                        break;
                    }
                }
            }
            track.maxTurn = Math.max(track.maxTurn, turn);
        }
    }

    private static class GameTrack {
        String youName;
        String oppName;
        // turn -> (isYou -> last-seen PlayerStateView that turn)
        Map<Integer, Map<Boolean, JsonNode>> lastSnapshot = new HashMap<>();
        Map<Boolean, Integer> commanderTurn = new HashMap<>();
        int maxTurn = 0;
    }

    /** A deck's stat line, keyed by (deckName, aiName) - "Veloci-Ramp-Tor"
     * piloted by RULE_BASED_V2 and the same deck piloted by FORGE_AI are
     * tracked completely separately, which is the entire point. */
    private static class DeckAiStats {
        int games;
        long[] landsAt = new long[TURN_CHECKPOINTS.length];
        int[] landsAtCount = new int[TURN_CHECKPOINTS.length];
        long[] creaturesAt = new long[TURN_CHECKPOINTS.length];
        int[] creaturesAtCount = new int[TURN_CHECKPOINTS.length];
        long[] manaSourcesAt = new long[TURN_CHECKPOINTS.length];
        int[] manaSourcesAtCount = new int[TURN_CHECKPOINTS.length];
        long[] handSizeAt = new long[TURN_CHECKPOINTS.length];
        int[] handSizeAtCount = new int[TURN_CHECKPOINTS.length];
        long[] lifeAt = new long[TURN_CHECKPOINTS.length];
        int[] lifeAtCount = new int[TURN_CHECKPOINTS.length];
        int commanderCastGames;
        long commanderCastTurnSum;
        int commanderByTurn5;
        long gameLengthSum;
    }

    private static String report(Map<String, GameTrack> games) {
        Map<String, DeckAiStats> byDeckAi = new TreeMap<>();
        for (GameTrack g : games.values()) {
            for (boolean isYou : new boolean[] {true, false}) {
                String fullName = isYou ? g.youName : g.oppName;
                if (fullName == null) {
                    continue;
                }
                String aiName = fullName.contains("(") ? fullName.substring(0, fullName.indexOf('(')).trim() : fullName;
                String deckName = fullName.contains("(") ? fullName.substring(fullName.indexOf('(') + 1, fullName.lastIndexOf(')')) : "?";
                String key = deckName + " | " + aiName;
                DeckAiStats s = byDeckAi.computeIfAbsent(key, k -> new DeckAiStats());
                s.games++;
                s.gameLengthSum += g.maxTurn;
                Integer cmdTurn = g.commanderTurn.get(isYou);
                if (cmdTurn != null) {
                    s.commanderCastGames++;
                    s.commanderCastTurnSum += cmdTurn;
                    if (cmdTurn <= 5) {
                        s.commanderByTurn5++;
                    }
                }
                for (int ci = 0; ci < TURN_CHECKPOINTS.length; ci++) {
                    JsonNode snap = latestSnapshotAtOrBefore(g, TURN_CHECKPOINTS[ci], isYou);
                    if (snap == null) {
                        continue;
                    }
                    int lands = 0;
                    int creatures = 0;
                    int manaSources = 0;
                    for (JsonNode c : snap.path("battlefield")) {
                        String typeLine = c.path("typeLine").asText("");
                        if (typeLine.contains("Land")) {
                            lands++;
                        }
                        if (typeLine.contains("Creature")) {
                            creatures++;
                        }
                        if (c.path("producesMana").asBoolean()) {
                            manaSources++;
                        }
                    }
                    s.landsAt[ci] += lands;
                    s.landsAtCount[ci]++;
                    s.creaturesAt[ci] += creatures;
                    s.creaturesAtCount[ci]++;
                    s.manaSourcesAt[ci] += manaSources;
                    s.manaSourcesAtCount[ci]++;
                    s.lifeAt[ci] += snap.path("life").asInt();
                    s.lifeAtCount[ci]++;
                    // handCount (not hand contents, which are hidden for
                    // "opp") is the only signal available for both sides -
                    // used uniformly so the stat is directly comparable.
                    s.handSizeAt[ci] += snap.path("handCount").asInt();
                    s.handSizeAtCount[ci]++;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Games tracked: ").append(games.size()).append("\n\n");
        for (var e : byDeckAi.entrySet()) {
            DeckAiStats s = e.getValue();
            sb.append("=== ").append(e.getKey()).append(" (n=").append(s.games).append(") ===\n");
            sb.append(String.format("  avg game length: %.1f turns%n", s.gameLengthSum / (double) s.games));
            sb.append(String.format("  commander cast: %d/%d games (%.0f%%), avg turn %.1f, by-turn-5 %d/%d (%.0f%%)%n",
                    s.commanderCastGames, s.games, 100.0 * s.commanderCastGames / s.games,
                    s.commanderCastGames > 0 ? s.commanderCastTurnSum / (double) s.commanderCastGames : Double.NaN,
                    s.commanderByTurn5, s.games, 100.0 * s.commanderByTurn5 / s.games));
            for (int ci = 0; ci < TURN_CHECKPOINTS.length; ci++) {
                int t = TURN_CHECKPOINTS[ci];
                sb.append(String.format("  by turn %d: lands=%.2f  manaSources=%.2f  creatures=%.2f  life=%.1f  handSize=%.2f  (n=%d)%n",
                        t, avg(s.landsAt[ci], s.landsAtCount[ci]), avg(s.manaSourcesAt[ci], s.manaSourcesAtCount[ci]),
                        avg(s.creaturesAt[ci], s.creaturesAtCount[ci]), avg(s.lifeAt[ci], s.lifeAtCount[ci]),
                        avg(s.handSizeAt[ci], s.handSizeAtCount[ci]), s.landsAtCount[ci]));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static double avg(long sum, int count) {
        return count == 0 ? Double.NaN : sum / (double) count;
    }

    /** The board as of the end of turn t: the last record seen with
     * turnNumber <= t (turns where the game ended early or a player never
     * got a logged decision that turn fall back to the most recent prior
     * turn's snapshot, same as "nothing changed" would look like). */
    private static JsonNode latestSnapshotAtOrBefore(GameTrack g, int t, boolean isYou) {
        JsonNode best = null;
        int bestTurn = -1;
        for (var entry : g.lastSnapshot.entrySet()) {
            int turn = entry.getKey();
            if (turn <= t && turn > bestTurn) {
                JsonNode snap = entry.getValue().get(isYou);
                if (snap != null) {
                    best = snap;
                    bestTurn = turn;
                }
            }
        }
        return best;
    }
}
