package forge.headless;

import forge.CardStorageReader;
import forge.ImageKeys;
import forge.StaticData;
import forge.ai.AIOption;
import forge.ai.LobbyPlayerAi;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.util.Lang;
import forge.util.Localizer;

import java.io.File;
import java.util.Arrays;
import java.util.EnumSet;

/**
 * Phase 1 spike: proves a full game can be constructed and played to
 * completion using only forge-core/forge-game/forge-ai, with no UI module
 * on the classpath and no dependency on ForgeConstants/GuiBase/FModel.
 */
public final class HeadlessSpike {

    public static void main(String[] args) {
        File resDir = resolveResDir();
        Localizer.getInstance().initialize("en-US", new File(resDir, "languages").getAbsolutePath());
        Lang.createInstance("en-US");
        ImageKeys.initializeDirs("", java.util.Collections.emptyMap(), "", "", "", "", "", "", "");
        loadCardData(resDir);

        RegisteredPlayer playerA = new RegisteredPlayer(buildMonoRedDeck("Player A's Deck"))
                .setPlayer(new LobbyPlayerAi("Player A", EnumSet.noneOf(AIOption.class)));
        RegisteredPlayer playerB = new RegisteredPlayer(buildMonoRedDeck("Player B's Deck"))
                .setPlayer(new LobbyPlayerAi("Player B", EnumSet.noneOf(AIOption.class)));

        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, Arrays.asList(playerA, playerB), "Headless Spike");
        Game game = match.createGame();

        long startMs = System.currentTimeMillis();
        match.startGame(game);
        long elapsedMs = System.currentTimeMillis() - startMs;

        System.out.println("Game finished after " + game.getPhaseHandler().getTurn()
                + " turns in " + elapsedMs + "ms.");
        System.out.println("Win condition: " + game.getOutcome().getWinCondition());
        System.out.println("Winning player: " + game.getOutcome().getWinningLobbyPlayer());
        for (String line : game.getOutcome().getOutcomeStrings()) {
            System.out.println(line);
        }
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

    private static void loadCardData(File resDir) {
        File cardsDir = new File(resDir, "cardsfolder");
        File editionsDir = new File(resDir, "editions");
        File blockDataDir = new File(resDir, "blockdata");

        CardStorageReader reader = new CardStorageReader(cardsDir.getAbsolutePath(), null, false);
        new StaticData(reader, null, editionsDir.getAbsolutePath(), editionsDir.getAbsolutePath(),
                blockDataDir.getAbsolutePath(), "Latest Art All Editions", true, false);
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

    private HeadlessSpike() { }
}
