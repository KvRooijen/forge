package forge.headless.protocol;

import java.util.List;
import java.util.Map;

public class PlayerStateView {
    public String name;
    public int life;
    public boolean isYou;
    public boolean isActiveTurn;
    public int handCount;
    public List<CardStateView> hand;
    public List<CardStateView> battlefield;
    public List<CardStateView> commandZone;
    /** Graveyard and exile are public zones in real Magic - sent in full
     * for any player, not just the viewer. */
    public List<CardStateView> graveyard;
    public List<CardStateView> exile;
    /** Library contents are hidden information even from their owner in a
     * real game - only the count is sent, never the cards, so peeking at
     * upcoming draws can't affect how you play. */
    public int libraryCount;
    /** Floating mana currently in this player's pool, keyed by color code
     * (W/U/B/R/G/C) - empties at the end of every phase/step per the real
     * rules, but with no visual indicator that was indistinguishable from
     * mana just silently vanishing. */
    public Map<String, Integer> floatingMana;
    /** The viewing (human) player's "stop and ask me" phases specifically
     * for turns belonging to this player - see RemotePlayerController's
     * stopPhasesByTurnPlayer for why this is per-player, not shared. */
    public List<String> stopAtPhases;

    public PlayerStateView() { }

    public PlayerStateView(String name, int life, boolean isYou, boolean isActiveTurn, int handCount,
            List<CardStateView> hand, List<CardStateView> battlefield, List<CardStateView> commandZone,
            List<CardStateView> graveyard, List<CardStateView> exile, int libraryCount,
            Map<String, Integer> floatingMana, List<String> stopAtPhases) {
        this.name = name;
        this.life = life;
        this.isYou = isYou;
        this.isActiveTurn = isActiveTurn;
        this.handCount = handCount;
        this.hand = hand;
        this.battlefield = battlefield;
        this.commandZone = commandZone;
        this.graveyard = graveyard;
        this.exile = exile;
        this.libraryCount = libraryCount;
        this.floatingMana = floatingMana;
        this.stopAtPhases = stopAtPhases;
    }
}
