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

    public static class StackItemView {
        public CardStateView source;
        public String description;

        public StackItemView() { }

        public StackItemView(CardStateView source, String description) {
            this.source = source;
            this.description = description;
        }
    }

    public GameStateView() { }

    public GameStateView(int turnNumber, String phase, String activePlayerName, List<PlayerStateView> players,
            List<StackItemView> stack, List<String> log) {
        this.turnNumber = turnNumber;
        this.phase = phase;
        this.activePlayerName = activePlayerName;
        this.players = players;
        this.stack = stack;
        this.log = log;
    }
}
