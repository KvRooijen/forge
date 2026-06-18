package forge.headless.protocol;

import java.util.List;

public class PlayerStateView {
    public String name;
    public int life;
    public boolean isYou;
    public boolean isActiveTurn;
    public int handCount;
    public List<CardStateView> hand;
    public List<CardStateView> battlefield;
    public List<CardStateView> commandZone;

    public PlayerStateView() { }

    public PlayerStateView(String name, int life, boolean isYou, boolean isActiveTurn, int handCount,
            List<CardStateView> hand, List<CardStateView> battlefield, List<CardStateView> commandZone) {
        this.name = name;
        this.life = life;
        this.isYou = isYou;
        this.isActiveTurn = isActiveTurn;
        this.handCount = handCount;
        this.hand = hand;
        this.battlefield = battlefield;
        this.commandZone = commandZone;
    }
}
