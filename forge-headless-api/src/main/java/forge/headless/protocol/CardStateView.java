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
    /** Room cards (Duskmourn) have two "doors", left and right - null for
     * non-Room cards. Side-specific (rather than the two unordered lists
     * this used to be) so the frontend can put a big lock icon on the
     * actual locked half instead of just listing door names in a corner. */
    public RoomDoor leftDoor;
    public RoomDoor rightDoor;
    /** Current effective keywords (Card.getUnhiddenKeywords()) - not just
     * what's printed on the card. Auras/equipment/anthems/etc. can grant
     * keywords (hexproof, flying, ...) that never show up in the static
     * card art the frontend renders, so without this the player would
     * have no way to see them from the UI at all. */
    public List<String> keywords;
    /** Id of the permanent this card (an Aura/Equipment/Fortification) is
     * currently attached to, if any - null otherwise. The board has no
     * other way to show which creature an aura/equipment belongs to,
     * since they just render as separate cards in the same battlefield
     * list. */
    public String attachedToId;
    /** Generic mana tax for casting this commander from the command zone
     * again (CR 903.8: +{2} per previous cast from the command zone this
     * game) - null for non-commander cards or when the tax is 0. */
    public Integer commanderTax;
    /** Colors (W/U/B/R/G/C) this card's mana abilities can actually
     * produce, from Card.canProduceColorMana() - real engine logic, not a
     * name-based guess, so it's accurate for duals/triomes/signets/Command
     * Tower, not just the 5 basics. Empty for cards with no mana ability. */
    public List<String> producedColors;
    /** Best-effort "this permanent enters the battlefield tapped" signal
     * (checks ReplacementEffects for the standard "CARDNAME enters
     * tapped." description, same heuristic forge-ai's own land-choice
     * logic uses and acknowledges as imperfect - conditional taplands
     * that only sometimes enter tapped aren't distinguished here). */
    public boolean entersTapped;
    /** True if the viewing player controls this card right now - lets a
     * decision-maker tell "my own creature" from "an opponent's" for any
     * Card-backed option (e.g. picking a target), without needing a
     * separate signal threaded through every call site individually. */
    public boolean controllerIsYou;
    /** Coarse, high-confidence-only classification of what this card's
     * own "when this enters" trigger does ("REMOVAL"/"SWEEPER"/"DRAW"/
     * "RAMP"), from RemotePlayerController.classifyEtbRole - null when
     * there's no ETB trigger, or it doesn't match the narrow set of
     * patterns classified with confidence. Without this, a vanilla
     * creature and one with a powerful ETB (the single most common value
     * pattern in modern Magic/Commander) score identically by stats
     * alone - see GenericSpellSequencer.valueOf. */
    public String etbRole;
    /** Same idea as etbRole, for this card's own "when this dies" trigger
     * - unlike an ETB (a one-time event already consumed once a creature
     * is sitting resolved on the battlefield), this is real ongoing value
     * *while the creature is alive*: it makes the creature strictly
     * better than a vanilla one of the same stats, win or lose the
     * eventual race to remove it - so unlike etbRole, this (discounted
     * for being conditional on actually dying) belongs in CreatureValue's
     * already-on-board scoring, not just the cast decision. */
    public String deathRole;
    /** Same idea again, for "whenever this attacks" - more reliably
     * realized than a death trigger (the controller chooses when to
     * attack) but still conditional, not guaranteed every turn. */
    public String attackRole;
    /** Precomputed team-buff value from this card's own static "lords
     * get bonus" abilities (e.g. "Other Elves you control get +1/+1") -
     * computed eagerly server-side via real Card.isValid() restriction
     * matching against the controller's actual creatures (see
     * RemotePlayerController.classifyAnthemValue), since a flattened
     * CardStateView can't be matched against a restriction string itself.
     * 0 when there's no static team buff, or it's not a plain-integer
     * magnitude classified with confidence. */
    public double anthemValue;

    public static class RoomDoor {
        public String name;
        public boolean locked;

        public RoomDoor() { }

        public RoomDoor(String name, boolean locked) {
            this.name = name;
            this.locked = locked;
        }
    }

    public CardStateView() { }

    public CardStateView(String id, String name, String manaCost, String typeLine,
            Integer power, Integer toughness, boolean tapped, boolean isCommander,
            boolean sick, Map<String, Integer> counters, boolean attacking,
            String attackingTarget, String blockingAttacker, boolean producesMana,
            RoomDoor leftDoor, RoomDoor rightDoor, List<String> keywords,
            String attachedToId, Integer commanderTax, List<String> producedColors, boolean entersTapped,
            boolean controllerIsYou, String etbRole, String deathRole, String attackRole, double anthemValue) {
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
        this.leftDoor = leftDoor;
        this.rightDoor = rightDoor;
        this.keywords = keywords;
        this.attachedToId = attachedToId;
        this.commanderTax = commanderTax;
        this.producedColors = producedColors;
        this.entersTapped = entersTapped;
        this.controllerIsYou = controllerIsYou;
        this.etbRole = etbRole;
        this.deathRole = deathRole;
        this.attackRole = attackRole;
        this.anthemValue = anthemValue;
    }
}
