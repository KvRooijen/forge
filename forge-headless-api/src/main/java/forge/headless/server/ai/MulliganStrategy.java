package forge.headless.server.ai;

import forge.headless.protocol.GameStateView;

public interface MulliganStrategy {
    boolean keepHand(GameStateView state);
}
