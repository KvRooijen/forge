package forge.headless.server.ai;

import forge.headless.protocol.DecisionRequest;
import forge.headless.protocol.GameStateView;

import java.util.List;

public interface AttackStrategy {
    /** Which of the offered attacker options should actually attack.
     * defenderName is whichever player this combat is actually against
     * (see DecisionRequest.defenderName) - null is possible (combat
     * against an entity whose controller couldn't be resolved) and
     * should be handled gracefully, not assumed non-null.
     *
     * Note: the protocol doesn't yet expose a defender *choice* - every
     * attacker is offered against whichever single defender
     * RemotePlayerController already picked, so this can't yet do
     * multiplayer "who do I attack" politics even if it wanted to. */
    List<String> chooseAttackers(List<DecisionRequest.Option> options, GameStateView state, String defenderName);
}
