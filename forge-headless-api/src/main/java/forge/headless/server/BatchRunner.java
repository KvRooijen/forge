package forge.headless.server;

import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameOutcome;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Headless all-AI batch runner: no WebSocket, no human seat, no frontend
 * at all - just N seats backed by InProcessAiChannel, run back-to-back in
 * one JVM (so JIT warmup is paid once for the whole batch, not once per
 * game) with structured per-game results for evaluating AI changes.
 *
 * Usage: java forge.headless.server.BatchRunner [--games N] [--players 2|4]
 *        [--seed N] [--decks "Deck A.dck,Deck B.dck,..."] [--timeout SECONDS]
 *        [--out path.csv]
 */
public class BatchRunner {

    public record GameResult(int index, List<String> decks, String winner, int turns, long elapsedMs, String error) { }

    public static void main(String[] args) {
        ForgeBootstrap.init();

        int numGames = intArg(args, "--games", 20);
        int numPlayers = intArg(args, "--players", 2);
        long seed = longArg(args, "--seed", System.nanoTime());
        int timeoutSeconds = intArg(args, "--timeout", 120);
        String outPath = stringArg(args, "--out", "batch_results.csv");
        String decksArg = stringArg(args, "--decks", null);

        List<String> deckPool = decksArg != null ? List.of(decksArg.split(","))
                : listPreconDeckNames();
        Random rng = new Random(seed);

        System.out.printf("Running %d game(s), %d player(s) each, seed=%d, timeout=%ds%n",
                numGames, numPlayers, seed, timeoutSeconds);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        List<GameResult> results = new ArrayList<>();
        long batchStart = System.nanoTime();
        for (int i = 0; i < numGames; i++) {
            List<String> decks = decksArg != null ? deckPool : pickDecks(deckPool, numPlayers, rng);
            GameResult r = runOneGame(executor, i, decks, timeoutSeconds);
            results.add(r);
            System.out.printf("[%d/%d] winner=%-30s turns=%-4d %5dms %s%n",
                    i + 1, numGames, r.winner() != null ? r.winner() : "(none/draw)",
                    r.turns(), r.elapsedMs(), r.error() != null ? "ERROR: " + r.error() : "");
        }
        executor.shutdownNow();
        long batchElapsedMs = (System.nanoTime() - batchStart) / 1_000_000;

        printSummary(results, batchElapsedMs);
        writeCsv(results, outPath);
        System.out.println("Wrote results to " + outPath);
    }

    private static GameResult runOneGame(ExecutorService executor, int index, List<String> deckNames, int timeoutSeconds) {
        long start = System.nanoTime();
        Future<GameOutcome> future = executor.submit(() -> playGame(deckNames));
        try {
            GameOutcome outcome = future.get(timeoutSeconds, TimeUnit.SECONDS);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            String winner = outcome.getWinningPlayer() != null
                    ? outcome.getWinningPlayer().getPlayer().getName() : null;
            return new GameResult(index, deckNames, winner, outcome.getLastTurnNumber(), elapsedMs, null);
        } catch (TimeoutException e) {
            future.cancel(true);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            return new GameResult(index, deckNames, null, -1, elapsedMs, "timeout after " + timeoutSeconds + "s");
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            return new GameResult(index, deckNames, null, -1, elapsedMs, e.toString());
        }
    }

    private static GameOutcome playGame(List<String> deckNames) {
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < deckNames.size(); i++) {
            Deck deck = ForgeBootstrap.loadPreconDeck(deckNames.get(i));
            String seatName = "AI" + (i + 1) + " (" + deckNames.get(i).replaceFirst(" \\[.*", "") + ")";
            // spectatorChannel is null - no human watching, so the
            // per-action broadcast (pushSpectatorUpdate) is a no-op for
            // every seat, exactly the overhead a batch run doesn't need.
            players.add(RegisteredPlayer.forCommander(deck)
                    .setPlayer(new LobbyPlayerRemote(seatName, new InProcessAiChannel(), null)));
        }
        GameRules rules = new GameRules(GameType.Commander);
        Match match = new Match(rules, players, "Batch");
        Game game = match.createGame();
        match.startGame(game);
        return game.getOutcome();
    }

    private static List<String> pickDecks(List<String> pool, int n, Random rng) {
        List<String> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, rng);
        return new ArrayList<>(shuffled.subList(0, Math.min(n, shuffled.size())));
    }

    private static List<String> listPreconDeckNames() {
        File[] files = ForgeBootstrap.preconDir().listFiles((dir, name) -> name.endsWith(".dck"));
        if (files == null || files.length == 0) {
            throw new IllegalStateException("No precon decks found in " + ForgeBootstrap.preconDir());
        }
        List<String> names = new ArrayList<>();
        for (File f : files) {
            names.add(f.getName());
        }
        return names;
    }

    private static void printSummary(List<GameResult> results, long batchElapsedMs) {
        int errors = 0;
        long totalGameMs = 0;
        java.util.Map<String, Integer> winsByPlayer = new java.util.LinkedHashMap<>();
        for (GameResult r : results) {
            totalGameMs += r.elapsedMs();
            if (r.error() != null) {
                errors++;
            } else if (r.winner() != null) {
                winsByPlayer.merge(r.winner(), 1, Integer::sum);
            }
        }
        System.out.println();
        System.out.printf("=== %d games in %dms (avg %.0fms/game) - %d error(s) ===%n",
                results.size(), batchElapsedMs, results.isEmpty() ? 0 : (double) totalGameMs / results.size(), errors);
        winsByPlayer.forEach((name, wins) -> System.out.printf("  %-30s %d win(s)%n", name, wins));
    }

    private static void writeCsv(List<GameResult> results, String path) {
        try (PrintWriter w = new PrintWriter(path)) {
            w.println("index,decks,winner,turns,elapsedMs,error");
            for (GameResult r : results) {
                w.printf("%d,\"%s\",%s,%d,%d,\"%s\"%n",
                        r.index(), String.join(" | ", r.decks()),
                        r.winner() != null ? r.winner() : "",
                        r.turns(), r.elapsedMs(),
                        r.error() != null ? r.error().replace("\"", "'") : "");
            }
        } catch (Exception e) {
            System.err.println("Failed to write CSV: " + e);
        }
    }

    private static int intArg(String[] args, String name, int fallback) {
        String v = stringArg(args, name, null);
        return v != null ? Integer.parseInt(v) : fallback;
    }

    private static long longArg(String[] args, String name, long fallback) {
        String v = stringArg(args, name, null);
        return v != null ? Long.parseLong(v) : fallback;
    }

    private static String stringArg(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}
