package forge.headless.server.ai;

import forge.headless.protocol.DecisionRequest;
import forge.headless.protocol.GameStateView;

import java.util.List;

public interface AttackStrategy {
    /** Which of the offered attacker options should actually attack.
     * Note: the protocol doesn't yet expose a defender choice - every
     * attacker is offered against whichever single defender
     * RemotePlayerController already picked, so this can't yet do
     * multiplayer "who do I attack" politics even if it wanted to. */
    List<String> chooseAttackers(List<DecisionRequest.Option> options, GameStateView state);
}
