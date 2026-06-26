package forge.game.mulligan;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public class LondonMulligan extends AbstractMulligan {
    public LondonMulligan(Player p, boolean firstMullFree) {
        super(p, firstMullFree);
    }

    @Override
    public boolean canMulligan() {
        // Strictly less than, not <=: tuckCardsDuringMulligan() == maxHandSize
        // means the *previous* mulligan already tucked the player's entire
        // hand (0 cards left). Allowing one more "mulligan" attempt past that
        // point is a real infinite loop, not just an edge case: the next
        // attempt finds an empty hand, AbstractMulligan.mulligan() bails out
        // early on `toMulligan.isEmpty()` without incrementing
        // timesMulliganed or redrawing, so canMulligan() evaluates to the
        // exact same true result forever - the player is asked "keep your
        // (permanently empty) hand?" indefinitely. Confirmed live: this was
        // the root cause of every multi-minute game timeout investigated
        // this session (millions of MULLIGAN_KEEP calls, cardsToReturn=7/
        // handSize=0 frozen identically every time) - not AI slowness at
        // all. Once tucking the whole hand would happen, the player should
        // simply be forced to keep instead of being offered a doomed retry.
        return !kept && tuckCardsDuringMulligan() < player.getMaxHandSize();
    }

    @Override
    public int handSizeAfterNextMulligan() {
        return player.getMaxHandSize();
    }

    @Override
    public void mulliganDraw() {
        player.drawCards(handSizeAfterNextMulligan());
        int tuckingCards = tuckCardsDuringMulligan();
        CardCollection hand = new CardCollection(player.getCardsIn(ZoneType.Hand));

        for (final Card c : player.getController().tuckCardsViaMulligan(hand, tuckingCards)) {
            player.getGame().getAction().moveToLibrary(c, -1, null);
        }
    }

    @Override
    public int tuckCardsDuringMulligan() {
        if (timesMulliganed == 0) {
            return 0;
        }

        int extraCard = firstMulliganFree ? 1 : 0;
        return timesMulliganed - extraCard;
    }
}
