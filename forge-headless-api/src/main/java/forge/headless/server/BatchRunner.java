package forge.headless.server;

import forge.LobbyPlayer;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameOutcome;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Headless all-AI batch/tournament runner: no WebSocket, no human seat, no
 * frontend at all - just N seats, each backed by whichever AiPlayerType
 * it's assigned, run back-to-back with structured per-game results for
 * evaluating AI changes.
 *
 * Two modes:
 *  - Plain batch: random decks from a pool, every seat the same AI type
 *    (or a fixed --ai list). Quick smoke-testing.
 *  - Tournament (--tournament): two AI types face off across every pair
 *    of decks in a pool, with sides swapped so deck choice can't bias the
 *    result, repeated --games-per-matchup times per pairing - enough
 *    games to get a meaningful win rate per AI type, not per deck.
 *
 * Parallelism is across *processes*, not threads: Game.java keeps a
 * shared `static int maxId` incremented with no synchronization (and
 * likely similar unguarded static state elsewhere in a codebase never
 * written for concurrent simulation) - running several Game instances at
 * once via threads in one JVM risks silently corrupting results. Spawning
 * separate JVM child processes (--workers N) sidesteps that entirely:
 * each one is fully isolated, at the cost of paying JIT/classload warmup
 * once per worker instead of once total.
 *
 * Usage:
 *   java forge.headless.server.BatchRunner --games N [--players 2|4]
 *        [--ai TYPE,TYPE,...] [--seed N] [--decks "A.dck,B.dck"]
 *        [--timeout SECONDS] [--out path.csv] [--workers N]
 *
 *   java forge.headless.server.BatchRunner --tournament
 *        --ai-a SIMPLE_HEURISTIC --ai-b FORGE_AI
 *        [--deck-pool "A.dck,B.dck,C.dck,D.dck"] [--games-per-matchup N]
 *        [--seed N] [--timeout SECONDS] [--out path.csv] [--workers N]
 */
public class BatchRunner {

    public record SeatSpec(String deckName, AiPlayerType aiType, String seatName) { }
    public record GameSpec(List<SeatSpec> seats) { }
    public record GameResult(int index, List<String> seatNames, String winner, int turns, long elapsedMs, String error) { }

    public static void main(String[] args) {
        int workers = intArg(args, "--workers", 1);
        Integer shardIndex = intArgOrNull(args, "--shard-index");
        boolean isChildShard = shardIndex != null;

        if (workers > 1 && !isChildShard) {
            runAsParent(args, workers);
            return;
        }

        ForgeBootstrap.init();

        boolean tournament = boolArg(args, "--tournament");
        int timeoutSeconds = intArg(args, "--timeout", 180);
        String outPath = stringArg(args, "--out", "batch_results.csv");
        long seed = longArg(args, "--seed", 12345L);

        List<GameSpec> allGames = tournament ? generateTournament(args, seed) : generatePlainBatch(args, seed);
        List<GameSpec> games = isChildShard ? sliceForShard(allGames, shardIndex, workers) : allGames;

        if (!isChildShard) {
            System.out.printf("Running %d game(s)%s%n", games.size(), tournament ? " (tournament)" : "");
        }

        List<GameResult> results = new ArrayList<>();
        long batchStart = System.nanoTime();
        for (int i = 0; i < games.size(); i++) {
            GameResult r = runOneGame(i, games.get(i), timeoutSeconds);
            results.add(r);
            System.out.printf("[%s%d/%d] winner=%-40s turns=%-4d %6dms %s%n",
                    isChildShard ? "shard" + shardIndex + " " : "", i + 1, games.size(),
                    r.winner() != null ? r.winner() : "(none/draw)",
                    r.turns(), r.elapsedMs(), r.error() != null ? "ERROR: " + r.error() : "");
        }
        long batchElapsedMs = (System.nanoTime() - batchStart) / 1_000_000;

        writeCsv(results, outPath);
        if (!isChildShard) {
            printSummary(results, batchElapsedMs);
            System.out.println("Wrote results to " + outPath);
        }
    }

    // ---- multi-process parallelism ----

    private static void runAsParent(String[] args, int workers) {
        ForgeBootstrap.init(); // needed to count games below (precon dir listing) when no --decks/--deck-pool override is given
        String outPath = stringArg(args, "--out", "batch_results.csv");
        List<File> shardFiles = new ArrayList<>();
        List<Process> children = new ArrayList<>();
        try {
            String javaBin = ProcessHandle.current().info().command().orElse("java");
            String classpath = System.getProperty("java.class.path");
            String resDir = System.getProperty("forge.res.dir");

            System.out.printf("Spawning %d worker process(es)...%n", workers);
            for (int i = 0; i < workers; i++) {
                File shardOut = new File(outPath + ".shard" + i + ".csv");
                File shardLog = new File(outPath + ".shard" + i + ".log");
                shardFiles.add(shardOut);

                List<String> cmd = new ArrayList<>();
                cmd.add(javaBin);
                cmd.add("-cp");
                cmd.add(classpath);
                if (resDir != null) {
                    cmd.add("-Dforge.res.dir=" + resDir);
                }
                cmd.add("forge.headless.server.BatchRunner");
                // strip the parent's own --out so it can't shadow the
                // shard-specific one appended below (stringArg returns the
                // first match, so leaving both in would make every child
                // silently write its real results to the parent's path)
                for (String a : stripFlag(args, "--out")) {
                    cmd.add(a);
                }
                cmd.add("--shard-index");
                cmd.add(String.valueOf(i));
                cmd.add("--out");
                cmd.add(shardOut.getPath());

                ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(shardLog);
                children.add(pb.start());
                System.out.printf("  worker %d started, log: %s%n", i, shardLog.getPath());
            }
            // generous backstop beyond the per-game --timeout: a worker
            // running its full shard sequentially needs roughly
            // shardSize * timeoutSeconds in the worst case
            int timeoutSeconds = intArg(args, "--timeout", 180);
            long perWorkerGames = (generateGameCount(args) + workers - 1) / workers;
            long backstopSeconds = Math.max(600, perWorkerGames * timeoutSeconds + 120);
            for (Process p : children) {
                if (!p.waitFor(backstopSeconds, TimeUnit.SECONDS)) {
                    System.err.println("Worker did not finish within " + backstopSeconds + "s, killing it");
                    p.destroyForcibly();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to spawn/await worker processes", e);
        }

        List<GameResult> merged = new ArrayList<>();
        for (File f : shardFiles) {
            merged.addAll(readCsv(f));
            f.delete();
        }
        writeCsv(merged, outPath);
        printSummary(merged, -1);
        System.out.println("Wrote merged results to " + outPath);
    }

    private static int generateGameCount(String[] args) {
        boolean tournament = boolArg(args, "--tournament");
        long seed = longArg(args, "--seed", 12345L);
        return (tournament ? generateTournament(args, seed) : generatePlainBatch(args, seed)).size();
    }

    private static List<GameSpec> sliceForShard(List<GameSpec> all, int shardIndex, int workers) {
        int n = all.size();
        int base = n / workers;
        int extra = n % workers;
        int start = shardIndex * base + Math.min(shardIndex, extra);
        int count = base + (shardIndex < extra ? 1 : 0);
        return new ArrayList<>(all.subList(start, Math.min(start + count, n)));
    }

    // ---- game-spec generation ----

    private static List<GameSpec> generatePlainBatch(String[] args, long seed) {
        int numGames = intArg(args, "--games", 20);
        int numPlayers = intArg(args, "--players", 2);
        String decksArg = stringArg(args, "--decks", null);
        String aiArg = stringArg(args, "--ai", AiPlayerType.SIMPLE_HEURISTIC.name());
        List<AiPlayerType> aiCycle = parseAiList(aiArg);

        List<String> deckPool = decksArg != null ? List.of(decksArg.split(",")) : listPreconDeckNames();
        Random rng = new Random(seed);

        List<GameSpec> games = new ArrayList<>();
        for (int g = 0; g < numGames; g++) {
            List<String> decks = decksArg != null ? deckPool : pickDecks(deckPool, numPlayers, rng);
            List<SeatSpec> seats = new ArrayList<>();
            for (int i = 0; i < decks.size(); i++) {
                AiPlayerType ai = aiCycle.get(i % aiCycle.size());
                String shortName = decks.get(i).replaceFirst(" \\[.*", "");
                seats.add(new SeatSpec(decks.get(i), ai, ai.name() + " (" + shortName + ")"));
            }
            games.add(new GameSpec(seats));
        }
        return games;
    }

    /** Every unordered pair of decks from the pool, with sides swapped (so
     * deck choice can't bias which AI looks stronger), repeated
     * --games-per-matchup times - 2 * C(pool, 2) * gamesPerMatchup games
     * total. Fully deterministic given the same args, no RNG involved -
     * the only randomness left is the game itself (shuffles, draws),
     * which is exactly what repeating each config is meant to sample. */
    private static List<GameSpec> generateTournament(String[] args, long seed) {
        AiPlayerType aiA = AiPlayerType.valueOf(stringArg(args, "--ai-a", AiPlayerType.SIMPLE_HEURISTIC.name()));
        AiPlayerType aiB = AiPlayerType.valueOf(stringArg(args, "--ai-b", AiPlayerType.FORGE_AI.name()));
        int gamesPerMatchup = intArg(args, "--games-per-matchup", 10);
        String poolArg = stringArg(args, "--deck-pool", null);
        List<String> pool = poolArg != null ? List.of(poolArg.split(","))
                : List.of("Veloci-Ramp-Tor [LCC] [2023].dck", "Sultai Arisen [TDC] [2025].dck",
                          "Temur Roar [TDC] [2025].dck", "Explorers of the Deep [LCC] [2023].dck");

        List<GameSpec> games = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            for (int j = i + 1; j < pool.size(); j++) {
                for (int g = 0; g < gamesPerMatchup; g++) {
                    games.add(matchup(pool.get(i), aiA, pool.get(j), aiB));
                    games.add(matchup(pool.get(j), aiA, pool.get(i), aiB));
                }
            }
        }
        return games;
    }

    private static GameSpec matchup(String deckA, AiPlayerType aiA, String deckB, AiPlayerType aiB) {
        return new GameSpec(List.of(
                new SeatSpec(deckA, aiA, aiA.name() + " (" + shortDeckName(deckA) + ")"),
                new SeatSpec(deckB, aiB, aiB.name() + " (" + shortDeckName(deckB) + ")")));
    }

    private static String shortDeckName(String deckFileName) {
        return deckFileName.replaceFirst(" \\[.*", "");
    }

    private static List<AiPlayerType> parseAiList(String csv) {
        List<AiPlayerType> list = new ArrayList<>();
        for (String s : csv.split(",")) {
            list.add(AiPlayerType.valueOf(s.trim()));
        }
        return list;
    }

    // ---- running games ----

    /** A fresh single-thread executor per game, not one shared across the
     * whole run: Forge's game loop doesn't check for interruption anywhere,
     * so a hung game's thread survives cancel()/shutdownNow() and keeps
     * running forever. With one executor per game, a stuck thread just
     * leaks (cleaned up by the daemon-thread factory at JVM exit) instead
     * of permanently wedging every game queued after it. */
    private static GameResult runOneGame(int index, GameSpec spec, int timeoutSeconds) {
        List<String> seatNames = spec.seats().stream().map(SeatSpec::seatName).toList();
        ExecutorService executor = Executors.newSingleThreadExecutor(BatchRunner::newDaemonThread);
        long start = System.nanoTime();
        Future<GameOutcome> future = executor.submit(() -> playGame(spec));
        try {
            GameOutcome outcome = future.get(timeoutSeconds, TimeUnit.SECONDS);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            String winner = outcome.getWinningPlayer() != null
                    ? outcome.getWinningPlayer().getPlayer().getName() : null;
            return new GameResult(index, seatNames, winner, outcome.getLastTurnNumber(), elapsedMs, null);
        } catch (TimeoutException e) {
            future.cancel(true);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            return new GameResult(index, seatNames, null, -1, elapsedMs, "timeout after " + timeoutSeconds + "s");
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            return new GameResult(index, seatNames, null, -1, elapsedMs, e.toString());
        } finally {
            executor.shutdown();
        }
    }

    private static Thread newDaemonThread(Runnable r) {
        Thread t = new Thread(r, "batch-game");
        t.setDaemon(true);
        return t;
    }

    private static GameOutcome playGame(GameSpec spec) {
        List<RegisteredPlayer> players = new ArrayList<>();
        for (SeatSpec seat : spec.seats()) {
            Deck deck = ForgeBootstrap.loadPreconDeck(seat.deckName());
            LobbyPlayer lobbyPlayer = seat.aiType().createLobbyPlayer(seat.seatName());
            players.add(RegisteredPlayer.forCommander(deck).setPlayer(lobbyPlayer));
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

    // ---- results: summary, CSV write/read ----

    /** Win rate by AI type (parsed back out of the seat-name prefix every
     * seat name is built with, e.g. "FORGE_AI (Temur Roar)") rather than
     * by individual seat/deck - that's the number the tournament is
     * actually for. Win rate by exact seat name (AI+deck combo) is also
     * shown, as a secondary signal for whether a particular deck swings
     * the matchup. */
    private static void printSummary(List<GameResult> results, long batchElapsedMs) {
        int errors = 0;
        long totalGameMs = 0;
        Map<String, int[]> winsByAiType = new LinkedHashMap<>(); // [wins, games]
        Map<String, int[]> winsBySeat = new LinkedHashMap<>();
        Pattern aiPrefix = Pattern.compile("^([A-Z0-9_]+) \\(");

        for (GameResult r : results) {
            totalGameMs += r.elapsedMs();
            if (r.error() != null) {
                errors++;
                continue;
            }
            for (String seatName : r.seatNames()) {
                winsBySeat.computeIfAbsent(seatName, k -> new int[2])[1]++;
                Matcher m = aiPrefix.matcher(seatName);
                String aiType = m.find() ? m.group(1) : seatName;
                winsByAiType.computeIfAbsent(aiType, k -> new int[2])[1]++;
            }
            if (r.winner() != null) {
                winsBySeat.computeIfAbsent(r.winner(), k -> new int[2])[0]++;
                Matcher m = aiPrefix.matcher(r.winner());
                String aiType = m.find() ? m.group(1) : r.winner();
                winsByAiType.computeIfAbsent(aiType, k -> new int[2])[0]++;
            }
        }

        System.out.println();
        if (batchElapsedMs >= 0) {
            System.out.printf("=== %d games in %dms (avg %.0fms/game) - %d error(s) ===%n",
                    results.size(), batchElapsedMs, results.isEmpty() ? 0 : (double) totalGameMs / results.size(), errors);
        } else {
            System.out.printf("=== %d games (merged from workers) - %d error(s) ===%n", results.size(), errors);
        }
        System.out.println("-- win rate by AI type --");
        winsByAiType.forEach((name, wl) -> System.out.printf("  %-20s %d/%d (%.1f%%)%n", name, wl[0], wl[1], 100.0 * wl[0] / wl[1]));
        System.out.println("-- win rate by seat (AI + deck) --");
        winsBySeat.forEach((name, wl) -> System.out.printf("  %-40s %d/%d (%.1f%%)%n", name, wl[0], wl[1], 100.0 * wl[0] / wl[1]));
    }

    private static void writeCsv(List<GameResult> results, String path) {
        try (PrintWriter w = new PrintWriter(path)) {
            w.println("index,seats,winner,turns,elapsedMs,error");
            for (GameResult r : results) {
                w.printf("%d,\"%s\",%s,%d,%d,\"%s\"%n",
                        r.index(), String.join(" | ", r.seatNames()),
                        r.winner() != null ? r.winner() : "",
                        r.turns(), r.elapsedMs(),
                        r.error() != null ? r.error().replace("\"", "'") : "");
            }
        } catch (Exception e) {
            System.err.println("Failed to write CSV: " + e);
        }
    }

    private static List<GameResult> readCsv(File path) {
        List<GameResult> results = new ArrayList<>();
        if (!path.exists()) {
            return results;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line = r.readLine(); // header
            while ((line = r.readLine()) != null) {
                List<String> fields = parseCsvLine(line);
                if (fields.size() < 6) {
                    continue;
                }
                List<String> seats = fields.get(1).isEmpty() ? List.of() : List.of(fields.get(1).split(" \\| "));
                results.add(new GameResult(Integer.parseInt(fields.get(0)), seats,
                        fields.get(2).isEmpty() ? null : fields.get(2),
                        Integer.parseInt(fields.get(3)), Long.parseLong(fields.get(4)),
                        fields.get(5).isEmpty() ? null : fields.get(5)));
            }
        } catch (Exception e) {
            System.err.println("Failed to read shard CSV " + path + ": " + e);
        }
        return results;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields;
    }

    // ---- arg parsing ----

    private static List<String> stripFlag(String[] args, String name) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals(name)) {
                i++; // also skip its value
                continue;
            }
            out.add(args[i]);
        }
        return out;
    }

    private static boolean boolArg(String[] args, String name) {
        for (String a : args) {
            if (a.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static int intArg(String[] args, String name, int fallback) {
        String v = stringArg(args, name, null);
        return v != null ? Integer.parseInt(v) : fallback;
    }

    private static Integer intArgOrNull(String[] args, String name) {
        String v = stringArg(args, name, null);
        return v != null ? Integer.parseInt(v) : null;
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
