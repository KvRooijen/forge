package forge.headless.protocol;

import java.util.List;

public class GameStateView {
    public int turnNumber;
    public String phase;
    public String activePlayerName;
    public List<PlayerStateView> players;
    public List<CardStateView> stack;
    public List<String> log;

    public GameStateView() { }

    public GameStateView(int turnNumber, String phase, String activePlayerName, List<PlayerStateView> players,
            List<CardStateView> stack, List<String> log) {
        this.turnNumber = turnNumber;
        this.phase = phase;
        this.activePlayerName = activePlayerName;
        this.players = players;
        this.stack = stack;
        this.log = log;
    }
}
