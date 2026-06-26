package forge.headless.server;

import forge.CardStorageReader;
import forge.ImageKeys;
import forge.StaticData;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.util.Lang;
import forge.util.Localizer;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * One-time static engine setup (card database, type lists, localization)
 * shared by every entry point that needs to actually run games - the
 * interactive GameServer and the headless BatchRunner alike. Pulled out of
 * GameServer so a batch run doesn't need to go through Javalin/WebSocket
 * setup at all to get a usable engine.
 */
public final class ForgeBootstrap {
    private static File resDir;
    private static boolean initialized;

    private ForgeBootstrap() { }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        resDir = resolveResDir();
        Localizer.getInstance().initialize("en-US", new File(resDir, "languages").getAbsolutePath());
        Lang.createInstance("en-US");
        ImageKeys.initializeDirs("", java.util.Collections.emptyMap(), "", "", "", "", "", "", "");

        File cardsDir = new File(resDir, "cardsfolder");
        File tokensDir = new File(resDir, "tokenscripts");
        File editionsDir = new File(resDir, "editions");
        File blockDataDir = new File(resDir, "blockdata");
        File setLookupDir = new File(resDir, "setlookup");
        // Lazy card loading - was false (eager: parses all ~33k card
        // scripts into memory on every single process startup, even
        // though one Commander pod only ever touches a few hundred of
        // them). StaticData.getCard() already has a working by-name
        // fallback (calls attemptToLoadCard() on a miss, loading just
        // that one card's file), so lazy loading still works correctly -
        // verified live before trusting it.
        //
        // The TOKEN reader deliberately stays eager (false): unlike
        // getCard(), TokenDb has no equivalent by-name fallback - it's
        // built ONCE from whatever the token reader returned at
        // construction time and never reconsulted, so a lazy/empty
        // initial load would permanently break every token-creating
        // effect (Sol Ring's elemental tokens, etc.) for the rest of the
        // game, not just slow them down. Tokens are also a much smaller
        // dataset than the full card pool, so eager-loading them isn't
        // the cost this change is targeting anyway.
        CardStorageReader reader = new CardStorageReader(cardsDir.getAbsolutePath(), null, true);
        // The simpler 8-arg StaticData constructor (no tokenReader) was
        // silently leaving StaticData.getAllTokens() null - every token-
        // creating effect (Sower of Discord, Sol Ring's... no, but plenty
        // of others) threw a NullPointerException the moment it resolved.
        // Mirrors FModel's own real constructor call (forge-gui's actual
        // startup), just with custom card/token readers omitted since we
        // don't support those.
        CardStorageReader tokenReader = new CardStorageReader(tokensDir.getAbsolutePath(), null, false);
        new StaticData(reader, tokenReader, null, null, editionsDir.getAbsolutePath(),
                editionsDir.getAbsolutePath(), blockDataDir.getAbsolutePath(), setLookupDir.getAbsolutePath(),
                "Latest Art All Editions", true, false, false, false);

        // Without this, forge.card.CardType.Constant.BASIC_TYPES (and
        // LAND_TYPES/CREATURE_TYPES/etc) stay permanently empty, since
        // they're normally populated by FModel.loadDynamicGamedata() during
        // the desktop client's own startup - which we don't run. The
        // practical effect: every basic land's subtype ("Plains", "Island",
        // ...) gets silently rejected as "not a known subtype" while
        // parsing its card type, so CardState's LandTraitChanges (which
        // injects basic lands' "{T}: Add {X}" mana ability based on
        // matching that subtype) never finds a match and adds nothing -
        // basic lands end up with zero mana abilities, completely
        // untappable, while every other land (whose ability comes from an
        // explicit script line, not subtype-driven injection) works fine.
        File typeListFile = new File(resDir, "lists/TypeLists.txt");
        Map<String, List<String>> typeSections = forge.util.FileSection.parseSections(
                forge.util.FileUtil.readFile(typeListFile.getAbsolutePath()));
        for (String sectionName : typeSections.keySet()) {
            forge.card.CardType.Helper.parseTypes(sectionName, typeSections.get(sectionName));
        }
        initialized = true;
    }

    public static Deck loadPreconDeck(String fileName) {
        File deckFile = new File(resDir, "quest/commanderprecons/" + fileName);
        Deck deck = DeckSerializer.fromFile(deckFile);
        if (deck == null) {
            throw new IllegalStateException("Could not load precon deck: " + deckFile.getAbsolutePath());
        }
        return deck;
    }

    public static File preconDir() {
        return new File(resDir, "quest/commanderprecons");
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
