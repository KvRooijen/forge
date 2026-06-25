package forge.headless.server.ai;

import forge.headless.protocol.DecisionRequest;
import forge.headless.protocol.GameStateView;
import forge.headless.protocol.PlayerStateView;

import java.util.List;

public interface LandPlayStrategy {
    /** Which land to play this turn from the legal "play land" options, or
     * null to skip the land drop entirely. `landOptions` is never empty
     * when this is called. */
    DecisionRequest.Option chooseLand(GameStateView state, PlayerStateView me, List<DecisionRequest.Option> landOptions);
}
