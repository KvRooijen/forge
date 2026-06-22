package forge.headless.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.CardStorageReader;
import forge.ImageKeys;
import forge.StaticData;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.headless.protocol.DecisionResponse;
import forge.headless.protocol.HttpChannel;
import forge.headless.protocol.WebSocketChannel;
import forge.util.Lang;
import forge.util.Localizer;
import io.javalin.Javalin;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Entry point for the headless engine server: one WebSocket endpoint for
 * the human seat, one outbound HTTP channel per AI seat pointed at the
 * Python ai-bridge. Seats/decks are still hardcoded; a real lobby API is
 * Phase 4 frontend work. This is a 4-player Commander pod using real
 * precon decklists shipped with Forge itself.
 */
public class GameServer {

    private static final Map<String, WebSocketChannel> channelsBySession = new ConcurrentHashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static File resDir;

    public static void main(String[] args) {
        bootstrapForgeStatics();

        String aiBridgeUrl = System.getProperty("ai.bridge.url", "http://localhost:8000/decide");
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
                Executors.newSingleThreadExecutor().submit(() -> runGame(channel, aiBridgeUrl));
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
                    channel.applyPhasePrefs(stopPhases);
                } else {
                    channel.onResponse(MAPPER.treeToValue(node, DecisionResponse.class));
                }
            });
            ws.onClose(ctx -> channelsBySession.remove(ctx.sessionId()));
        });

        System.out.println("GameServer listening on ws://localhost:" + port + "/ws/game");
        System.out.println("AI seat will call out to: " + aiBridgeUrl);
    }

    private static void runGame(WebSocketChannel humanChannel, String aiBridgeUrl) {
        try {
            RegisteredPlayer human = RegisteredPlayer.forCommander(
                    loadPreconDeck("Subjective Reality [C18] [2018].dck"))
                    .setPlayer(new LobbyPlayerRemote("Human (Aminatou)", humanChannel, humanChannel));
            // All seats - human and AI - go through RemotePlayerController.
            // AI seats route over HTTP to the same ai-bridge stub (currently
            // random); see RemotePlayerController's class comment for why it
            // no longer embeds PlayerControllerAi as a blanket fallback. Each
            // also gets a reference to the human's WebSocket as a
            // "spectator channel" so the human's view stays live while AI
            // seats act, not just during their own turn.
            RegisteredPlayer ai1 = RegisteredPlayer.forCommander(
                    loadPreconDeck("Veloci-Ramp-Tor [LCC] [2023].dck"))
                    .setPlayer(new LobbyPlayerRemote("AI (Pantlaza)", new HttpChannel(aiBridgeUrl), humanChannel));
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

    private static Deck loadPreconDeck(String fileName) {
        File deckFile = new File(resDir, "quest/commanderprecons/" + fileName);
        Deck deck = DeckSerializer.fromFile(deckFile);
        if (deck == null) {
            throw new IllegalStateException("Could not load precon deck: " + deckFile.getAbsolutePath());
        }
        return deck;
    }

    private static void bootstrapForgeStatics() {
        resDir = resolveResDir();
        Localizer.getInstance().initialize("en-US", new File(resDir, "languages").getAbsolutePath());
        Lang.createInstance("en-US");
        ImageKeys.initializeDirs("", java.util.Collections.emptyMap(), "", "", "", "", "", "", "");

        File cardsDir = new File(resDir, "cardsfolder");
        File editionsDir = new File(resDir, "editions");
        File blockDataDir = new File(resDir, "blockdata");
        CardStorageReader reader = new CardStorageReader(cardsDir.getAbsolutePath(), null, false);
        new StaticData(reader, null, editionsDir.getAbsolutePath(), editionsDir.getAbsolutePath(),
                blockDataDir.getAbsolutePath(), "Latest Art All Editions", true, false);
    }

    private static File resolveResDir() {
        String override = System.getProperty("forge.res.dir");
        if (override != null) {
            return new File(override);
        }
        for (String candidate : new String[] {"../forge-gui/res", "forge-gui/res"}) {
            File dir = new File(candidate);
            if (dir.isDirectory()) {
                return dir;
            }
        }
        throw new IllegalStateException(
                "Could not locate forge-gui/res; pass -Dforge.res.dir=<path to forge-gui/res>");
    }
}
