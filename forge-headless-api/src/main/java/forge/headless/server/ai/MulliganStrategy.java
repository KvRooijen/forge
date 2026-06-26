package forge.headless.server.ai;

import forge.headless.protocol.GameStateView;

public interface MulliganStrategy {
    /** cardsToReturn is how many cards this round would tuck back into the
     * library if not kept (0 on a fresh, never-mulliganed hand under the
     * London rule, growing with each subsequent mulligan) - lets a strategy
     * lower its standards as it digs deeper, rather than applying the same
     * bar to every hand regardless of how many have already been rejected. */
    boolean keepHand(GameStateView state, int cardsToReturn);
}
