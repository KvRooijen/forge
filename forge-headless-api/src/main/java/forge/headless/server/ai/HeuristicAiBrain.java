package forge.headless.server.ai;

/**
 * Composition root for one AI seat's decision-making - one slot per
 * category, each independently swappable. Every slot defaults to a
 * generic/deck-agnostic implementation; a future deck-specific AI
 * overrides only the categories that actually need different behavior
 * for that deck (e.g. a combo deck's lethal detection) via the Builder,
 * and falls back to the generic module for everything else.
 */
public class HeuristicAiBrain {
    public final ThreatAssessor threatAssessor;
    public final LandPlayStrategy landPlayStrategy;
    public final SpellSequencer spellSequencer;
    public final AttackStrategy attackStrategy;
    public final ManaPaymentStrategy manaPaymentStrategy;
    public final ListChoiceStrategy listChoiceStrategy;
    public final TriggerStrategy triggerStrategy;
    public final MulliganStrategy mulliganStrategy;

    private HeuristicAiBrain(Builder b) {
        this.threatAssessor = b.threatAssessor;
        this.landPlayStrategy = b.landPlayStrategy;
        this.spellSequencer = b.spellSequencer;
        this.attackStrategy = b.attackStrategy;
        this.manaPaymentStrategy = b.manaPaymentStrategy;
        this.listChoiceStrategy = b.listChoiceStrategy;
        this.triggerStrategy = b.triggerStrategy;
        this.mulliganStrategy = b.mulliganStrategy;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static HeuristicAiBrain generic() {
        return builder().build();
    }

    public static class Builder {
        private ThreatAssessor threatAssessor = new GenericThreatAssessor();
        private LandPlayStrategy landPlayStrategy = new GenericLandPlayStrategy();
        private SpellSequencer spellSequencer = new GenericSpellSequencer();
        private AttackStrategy attackStrategy = new GenericAttackStrategy();
        private ManaPaymentStrategy manaPaymentStrategy = new GenericManaPaymentStrategy();
        private ListChoiceStrategy listChoiceStrategy = new GenericListChoiceStrategy();
        private TriggerStrategy triggerStrategy = new GenericTriggerStrategy();
        private MulliganStrategy mulliganStrategy = new GenericMulliganStrategy();

        public Builder threatAssessor(ThreatAssessor v) { this.threatAssessor = v; return this; }
        public Builder landPlayStrategy(LandPlayStrategy v) { this.landPlayStrategy = v; return this; }
        public Builder spellSequencer(SpellSequencer v) { this.spellSequencer = v; return this; }
        public Builder attackStrategy(AttackStrategy v) { this.attackStrategy = v; return this; }
        public Builder manaPaymentStrategy(ManaPaymentStrategy v) { this.manaPaymentStrategy = v; return this; }
        public Builder listChoiceStrategy(ListChoiceStrategy v) { this.listChoiceStrategy = v; return this; }
        public Builder triggerStrategy(TriggerStrategy v) { this.triggerStrategy = v; return this; }
        public Builder mulliganStrategy(MulliganStrategy v) { this.mulliganStrategy = v; return this; }

        public HeuristicAiBrain build() {
            return new HeuristicAiBrain(this);
        }
    }
}
