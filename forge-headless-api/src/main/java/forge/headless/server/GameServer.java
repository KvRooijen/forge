package forge.headless.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.headless.protocol.DecisionResponse;
import forge.headless.protocol.WebSocketChannel;
import io.javalin.Javalin;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Entry point for the headless engine server: one WebSocket endpoint for
 * the human seat, one in-process AI seat. Seats/decks are still
 * hardcoded; a real lobby API is Phase 4 frontend work. This is a 4-player
 * Commander pod using real precon decklists shipped with Forge itself.
 */
public class GameServer {

    private static final Map<String, WebSocketChannel> channelsBySession = new ConcurrentHashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) {
        ForgeBootstrap.init();

        int port = Integer.getInteger("server.port", 7070);

        Javalin app = Javalin.create().start(port);

        app.ws("/ws/game", ws -> {
            ws.onConnect(ctx -> {
                // Default Jetty idle timeout is 30s, far too short for a human
                // actually thinking about a decision - they'd get silently
                // disconnected mid-game with no visible error.
                ctx.session.setIdleTimeout(java.time.Duration.ofHours(2));
                WebSocketChannel channel = new WebSocketChannel(ctx);
                channelsBySession.put(ctx.sessionId(), channel);
                Executors.newSingleThreadExecutor().submit(() -> runGame(channel));
            });
            ws.onMessage(ctx -> {
                WebSocketChannel channel = channelsBySession.get(ctx.sessionId());
                if (channel == null) {
                    return;
                }
                JsonNode node = MAPPER.readTree(ctx.message());
                if (node.has("stopPhases")) {
                    Set<String> stopPhases = new HashSet<>();
                    node.get("stopPhases").forEach(n -> stopPhases.add(n.asText()));
                    String forPlayer = node.has("forPlayer") ? node.get("forPlayer").asText() : null;
                    channel.applyPhasePrefs(forPlayer, stopPhases);
                } else {
                    channel.onResponse(MAPPER.treeToValue(node, DecisionResponse.class));
                }
            });
            ws.onClose(ctx -> channelsBySession.remove(ctx.sessionId()));
        });

        System.out.println("GameServer listening on ws://localhost:" + port + "/ws/game");
    }

    private static void runGame(WebSocketChannel humanChannel) {
        try {
            RegisteredPlayer human = RegisteredPlayer.forCommander(
                    ForgeBootstrap.loadPreconDeck("Sultai Arisen [TDC] [2025].dck"))
                    .setPlayer(new LobbyPlayerRemote("Human (Teval, the Balanced Scale)", humanChannel, humanChannel));
            // All seats - human and AI - go through RemotePlayerController.
            // The AI seat runs the heuristic AI in-process (InProcessAiChannel)
            // instead of round-tripping over HTTP to a separate process - see
            // its class comment. Each non-human seat also gets a reference to
            // the human's WebSocket as a "spectator channel" so the human's
            // view stays live while AI seats act, not just during their own
            // turn.
            RegisteredPlayer ai1 = RegisteredPlayer.forCommander(
                    ForgeBootstrap.loadPreconDeck("Veloci-Ramp-Tor [LCC] [2023].dck"))
                    .setPlayer(new LobbyPlayerRemote("AI (Pantlaza)", new InProcessAiChannel(), humanChannel));
            // Temporarily 1v1 (Hakbal/Eshki seats disabled) for easier debugging.

            GameRules rules = new GameRules(GameType.Commander);
            Match match = new Match(rules, List.of(human, ai1), "Playtest");
            Game game = match.createGame();
            match.startGame(game);

            System.out.println("Game finished: " + game.getOutcome().getWinCondition());
            humanChannel.sendGameOver(game.getOutcome().getWinCondition().toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
