package forge.headless.protocol;

import java.util.List;

public class GameStateView {
    public int turnNumber;
    public List<PlayerStateView> players;
    public List<CardStateView> stack;

    public GameStateView() { }

    public GameStateView(int turnNumber, List<PlayerStateView> players, List<CardStateView> stack) {
        this.turnNumber = turnNumber;
        this.players = players;
        this.stack = stack;
    }
}
