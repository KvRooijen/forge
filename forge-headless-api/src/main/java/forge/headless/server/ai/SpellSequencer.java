package forge.headless.server.ai;

import forge.headless.protocol.DecisionRequest;
import forge.headless.protocol.GameStateView;

import java.util.List;
import java.util.Set;

/** Covers CHOOSE_SPELL_ABILITY for non-land plays - picking which spell/
 * ability to cast/activate this priority window, and in what order across
 * a turn. Land selection is LandPlayStrategy's job, not this one's. */
public interface SpellSequencer {
    /** excludedCardIds are cards already known unpayable this turn (see
     * RuleBasedAiChannel's retry tracking) - tracking *which* ids those
     * are is the caller's bookkeeping, not this strategy's. Returns the
     * chosen option, or null to pass priority. */
    DecisionRequest.Option chooseSpell(List<DecisionRequest.Option> nonLandOptions, GameStateView state, Set<String> excludedCardIds);
}
