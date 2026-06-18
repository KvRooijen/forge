package forge.headless.server;

import forge.CardStorageReader;
import forge.ImageKeys;
import forge.StaticData;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.headless.protocol.DecisionResponse;
import forge.headless.protocol.HttpChannel;
import forge.headless.protocol.WebSocketChannel;
import forge.item.PaperCard;
import forge.util.Lang;
import forge.util.Localizer;
import io.javalin.Javalin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Entry point for the headless engine server: one WebSocket endpoint for
 * the human seat, one outbound HTTP channel per AI seat pointed at the
 * Python ai-bridge. Decks are still hardcoded; real deck import is
 * Phase 5. This is a 3-player Commander pod: one human seat, two AI seats.
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
            RegisteredPlayer human = RegisteredPlayer.forCommander(buildKrenkoGoblinsDeck("Human's Deck"))
                    .setPlayer(new LobbyPlayerRemote("Human", humanChannel));
            RegisteredPlayer ai1 = RegisteredPlayer.forCommander(buildKrenkoGoblinsDeck("AI 1's Deck"))
                    .setPlayer(new LobbyPlayerRemote("AI 1", new HttpChannel(aiBridgeUrl)));
            RegisteredPlayer ai2 = RegisteredPlayer.forCommander(buildKrenkoGoblinsDeck("AI 2's Deck"))
                    .setPlayer(new LobbyPlayerRemote("AI 2", new HttpChannel(aiBridgeUrl)));

            GameRules rules = new GameRules(GameType.Commander);
            Match match = new Match(rules, List.of(human, ai1, ai2), "Playtest");
            Game game = match.createGame();
            match.startGame(game);

            System.out.println("Game finished: " + game.getOutcome().getWinCondition());
            humanChannel.sendGameOver(game.getOutcome().getWinCondition().toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Resolves a commander + main deck list against Forge's card database.
     * Throws immediately with the offending name if a card can't be found -
     * this is the same resolution step real decklist import will need.
     */
    private static Deck resolveDeck(String name, String commanderName, List<String> mainCardNames) {
        Deck deck = new Deck(name);
        deck.getOrCreate(DeckSection.Commander).add(resolveCard(commanderName));
        CardPool main = deck.getMain();
        for (String cardName : mainCardNames) {
            main.add(resolveCard(cardName));
        }
        return deck;
    }

    private static PaperCard resolveCard(String name) {
        PaperCard card = StaticData.instance().getCommonCards().getCard(name);
        if (card == null) {
            throw new IllegalArgumentException("Unknown card: " + name);
        }
        return card;
    }

    private static Deck buildKrenkoGoblinsDeck(String name) {
        List<String> spells = new ArrayList<>(List.of(
                "Goblin Bushwhacker", "Goblin Warchief", "Goblin Matron", "Goblin Recruiter",
                "Goblin Piledriver", "Mogg War Marshal", "Hellrider", "Fanatical Firebrand",
                "Reckless One", "War Horn", "Lightning Strike", "Fireblast", "Chain Lightning",
                "Browbeat", "Wheel of Fortune", "Burning of Xinye", "Rite of Flame", "Dragon Fodder",
                "Krenko, Tin Street Kingpin", "Mardu Strike Leader", "Skirk Commando",
                "Boggart Shenanigans", "Goblin Sharpshooter", "Goblin Lackey", "Lightning Greaves",
                "Skullclamp", "Goblin Chieftain", "Goblin King", "Skirk Prospector", "Mogg Fanatic",
                "Lightning Bolt", "Sol Ring", "Arcane Signet", "Commander's Sphere", "Swiftfoot Boots"));
        Deck deck = resolveDeck(name, "Krenko, Mob Boss", spells);
        deck.getMain().add(resolveCard("Mountain"), 28);
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
