package forge.headless.protocol;

import java.util.List;
import java.util.Map;

/**
 * Minimal card snapshot for the frontend. Deliberately not Forge's own
 * CardView - that's wired for Swing/libGDX rendering and Trackable
 * serialization; a flat DTO is simpler to keep in sync with a web client.
 */
public class CardStateView {
    public String id;
    public String name;
    public String manaCost;
    public String typeLine;
    public Integer power;
    public Integer toughness;
    public boolean tapped;
    public boolean isCommander;
    public boolean sick;
    public Map<String, Integer> counters;
    public boolean attacking;
    /** Display name of whoever/whatever this card is attacking, if any. */
    public String attackingTarget;
    /** Name of the attacker this card is blocking, if any. */
    public String blockingAttacker;
    /** Has at least one mana ability - lets the frontend group mana rocks
     * onto the same row as lands instead of with other artifacts. */
    public boolean producesMana;
    /** Room cards (Duskmourn) have two "doors" - names of the door(s)
     * currently unlocked / still locked, empty for non-Room cards. */
    public List<String> unlockedRoomNames;
    public List<String> lockedRoomNames;

    public CardStateView() { }

    public CardStateView(String id, String name, String manaCost, String typeLine,
            Integer power, Integer toughness, boolean tapped, boolean isCommander,
            boolean sick, Map<String, Integer> counters, boolean attacking,
            String attackingTarget, String blockingAttacker, boolean producesMana,
            List<String> unlockedRoomNames, List<String> lockedRoomNames) {
        this.id = id;
        this.name = name;
        this.manaCost = manaCost;
        this.typeLine = typeLine;
        this.power = power;
        this.toughness = toughness;
        this.tapped = tapped;
        this.isCommander = isCommander;
        this.sick = sick;
        this.counters = counters;
        this.attacking = attacking;
        this.attackingTarget = attackingTarget;
        this.blockingAttacker = blockingAttacker;
        this.producesMana = producesMana;
        this.unlockedRoomNames = unlockedRoomNames;
        this.lockedRoomNames = lockedRoomNames;
    }
}
