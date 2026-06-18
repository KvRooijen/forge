package forge.headless.server;

import forge.CardStorageReader;
import forge.ImageKeys;
import forge.StaticData;
import forge.deck.CardPool;
import forge.deck.Deck;
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
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Entry point for the headless engine server: one WebSocket endpoint for
 * the human seat, one outbound HTTP channel per AI seat pointed at the
 * Python ai-bridge. Deck/seat setup here is still hardcoded - real lobby
 * setup (N seats, deck import) is Phase 3.
 */
public class GameServer {

    private static final Map<String, WebSocketChannel> channelsBySession = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        bootstrapForgeStatics();

        String aiBridgeUrl = System.getProperty("ai.bridge.url", "http://localhost:8000/decide");
        int port = Integer.getInteger("server.port", 7070);

        Javalin app = Javalin.create().start(port);

        app.ws("/ws/game", ws -> {
            ws.onConnect(ctx -> {
                WebSocketChannel channel = new WebSocketChannel(ctx);
                channelsBySession.put(ctx.sessionId(), channel);
                Executors.newSingleThreadExecutor().submit(() -> runGame(channel, aiBridgeUrl));
            });
            ws.onMessage(ctx -> {
                WebSocketChannel channel = channelsBySession.get(ctx.sessionId());
                if (channel != null) {
                    channel.onResponse(ctx.messageAsClass(DecisionResponse.class));
                }
            });
            ws.onClose(ctx -> channelsBySession.remove(ctx.sessionId()));
        });

        System.out.println("GameServer listening on ws://localhost:" + port + "/ws/game");
        System.out.println("AI seat will call out to: " + aiBridgeUrl);
    }

    private static void runGame(WebSocketChannel humanChannel, String aiBridgeUrl) {
        try {
            RegisteredPlayer human = new RegisteredPlayer(buildMonoRedDeck("Human's Deck"))
                    .setPlayer(new LobbyPlayerRemote("Human", humanChannel));
            RegisteredPlayer ai = new RegisteredPlayer(buildMonoRedDeck("AI's Deck"))
                    .setPlayer(new LobbyPlayerRemote("AI", new HttpChannel(aiBridgeUrl)));

            GameRules rules = new GameRules(GameType.Constructed);
            Match match = new Match(rules, Arrays.asList(human, ai), "Playtest");
            Game game = match.createGame();
            match.startGame(game);

            System.out.println("Game finished: " + game.getOutcome().getWinCondition());
            humanChannel.sendGameOver(game.getOutcome().getWinCondition().toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Deck buildMonoRedDeck(String name) {
        Deck deck = new Deck(name);
        CardPool main = deck.getMain();
        main.add(StaticData.instance().getCommonCards().getCard("Mountain"), 20);
        main.add(StaticData.instance().getCommonCards().getCard("Goblin Piker"), 6);
        main.add(StaticData.instance().getCommonCards().getCard("Hill Giant"), 6);
        main.add(StaticData.instance().getCommonCards().getCard("Shock"), 8);
        return deck;
    }

    private static void bootstrapForgeStatics() {
        File resDir = resolveResDir();
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
