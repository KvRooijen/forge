package forge.headless.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import forge.headless.protocol.DecisionRequest;
import forge.headless.protocol.DecisionResponse;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional, opt-in JSONL dump of every single AI decision (request +
 * response, full board context included) for later offline review -
 * either simple automated invariant checks or LLM-assisted reading.
 * Deliberately NOT sampled or filtered to "interesting" moments: a
 * systemic small mistake (e.g. always skipping an obviously-correct
 * turn-1 land drop) would never surface from spot-checking dramatic
 * turns, but would jump out immediately from a scan across every
 * decision in a batch - and we have no way to know in advance which
 * decisions are "interesting" without already knowing what's wrong.
 *
 * Off by default (one volatile-read-equivalent null check per decision,
 * no other cost) - enabled by setting the forge.headless.decisionLog
 * system property to a file path. One JSONL file per JVM process;
 * BatchRunner's --workers spawns separate processes, so point each
 * worker at its own path the same way --out is already sharded per
 * worker, or just enable for a single-worker run when this is the goal.
 *
 * Hooked at RuleBasedAiChannel.ask() specifically (not somewhere in
 * RemotePlayerController) - that's the one place every decision for a
 * RULE_BASED_V2 seat already flows through with the exact DecisionRequest
 * (full GameStateView - turn, phase, stack, every player's life/board/
 * graveyard/exile/hand-if-it's-mine, floating mana, counters - already
 * scoped to exactly what that seat could legitimately see) and
 * DecisionResponse together, so logging from here costs nothing beyond
 * serializing data this class already has in hand. It also means logging
 * is automatically scoped to RULE_BASED_V2 seats only (SIMPLE_HEURISTIC/
 * FORGE_AI use different channel classes), which is exactly the AI this
 * project cares about auditing.
 */
public final class DecisionLogger {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BufferedWriter WRITER = openConfiguredWriter();
    private static final AtomicLong SEQ = new AtomicLong();

    private DecisionLogger() { }

    public static boolean isEnabled() {
        return WRITER != null;
    }

    private static BufferedWriter openConfiguredWriter() {
        String path = System.getProperty("forge.headless.decisionLog");
        if (path == null || path.isEmpty()) {
            return null;
        }
        try {
            return Files.newBufferedWriter(Path.of(path), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[DecisionLogger] failed to open " + path + ", logging disabled: " + e);
            return null;
        }
    }

    /**
     * seatChannelId scopes records to one seat's one game (a fresh
     * RuleBasedAiChannel is created per seat per game - see
     * AiPlayerType.RULE_BASED_V2 / BatchRunner.playGame) - it does NOT
     * correlate the two seats of the same actual game with each other.
     * Each record is self-contained for judging "was this decision
     * reasonable given what this seat knew" without needing the
     * opponent's own log, which is all this is built for so far -
     * cross-seat correlation (reconstructing one whole game from both
     * sides) would need a shared id threaded down from BatchRunner, not
     * yet added since nothing needs it yet.
     */
    public static synchronized void log(String seatChannelId, String seatName, DecisionRequest request, DecisionResponse response) {
        if (WRITER == null) {
            return;
        }
        try {
            Record r = new Record();
            r.seq = SEQ.incrementAndGet();
            r.timestamp = Instant.now().toString();
            r.seatChannelId = seatChannelId;
            r.seatName = seatName;
            r.request = request;
            r.response = response;
            WRITER.write(MAPPER.writeValueAsString(r));
            WRITER.write("\n");
            // Flushed every line, trading throughput for crash-safety -
            // correct for a deliberately-enabled diagnostic run, not for
            // a throughput-oriented batch (those should leave this off).
            WRITER.flush();
        } catch (IOException e) {
            System.err.println("[DecisionLogger] write failed: " + e);
        }
    }

    private static class Record {
        public long seq;
        public String timestamp;
        public String seatChannelId;
        public String seatName;
        public DecisionRequest request;
        public DecisionResponse response;
    }
}
