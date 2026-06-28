package forge.headless.protocol;

import java.util.List;

public class GameStateView {
    public int turnNumber;
    public String phase;
    public String activePlayerName;
    public List<PlayerStateView> players;
    /** Every item actually on the stack (Game.getStack()), not just spells -
     * triggered and activated abilities go on the stack too but never put
     * a card into the engine's separate "stack zone", which used to be the
     * only thing rendered here. */
    public List<StackItemView> stack;
    public List<String> log;
    /** Name of whoever currently holds the Monarch/Initiative emblem, or
     * null if neither is in play this game - these affect every player's
     * turn (extra draw, dungeon-venturing) but were otherwise invisible
     * unless you happened to remember who picked it up. */
    public String monarch;
    public String hasInitiative;
    /** "Day" or "Night" once the day/night cycle has actually started
     * (affects daybound/nightbound cards table-wide), null before then -
     * matches Game.isDay()/isNight() both being false at game start. */
    public String dayTime;

    public static class StackItemView {
        public CardStateView source;
        public String description;
        /** Same coarse REMOVAL/SWEEPER/DRAW/RAMP/CREATURE classification
         * as DecisionRequest.Option.spellRole (see its javadoc), computed
         * the same way (RemotePlayerController.classifySpellRole) but for
         * *any* spell on the stack, not just options offered to the
         * viewer - the stack is a public zone, so this is the same
         * information either player could see for themselves, just
         * pre-classified. Lets offline analysis (DecisionLogStats) count
         * removal/sweeper/ramp spells cast by *either* player, not only
         * the logging side - source.controllerIsYou says which. Null for
         * non-spell stack items (triggered/activated abilities) and
         * unclassified spells, same as Option.spellRole. */
        public String spellRole;

        public StackItemView() { }

        public StackItemView(CardStateView source, String description) {
            this.source = source;
            this.description = description;
        }
    }

    public GameStateView() { }

    public GameStateView(int turnNumber, String phase, String activePlayerName, List<PlayerStateView> players,
            List<StackItemView> stack, List<String> log, String monarch, String hasInitiative, String dayTime) {
        this.turnNumber = turnNumber;
        this.phase = phase;
        this.activePlayerName = activePlayerName;
        this.players = players;
        this.stack = stack;
        this.log = log;
        this.monarch = monarch;
        this.hasInitiative = hasInitiative;
        this.dayTime = dayTime;
    }
}
