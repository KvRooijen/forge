package forge.headless.server;

import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;

/**
 * Every AI implementation pluggable into a seat for evaluation/play -
 * the registry "different AI versions" boils down to. Each is just a
 * different way to produce a {@link LobbyPlayer} for a seat name; nothing
 * about BatchRunner or GameServer needs to know which decision-making
 * approach is actually behind it.
 *
 * forge-ai's own AI (FORGE_AI) is intentionally *not* reimplemented
 * against our minimal protocol - its evaluator classes are deeply coupled
 * to PlayerControllerAi internals (instanceof checks, shared per-ability
 * heuristics) that would be enormous and fragile to faithfully port. It's
 * wired in as-is via its own existing LobbyPlayerAi, which already
 * produces a real PlayerController; this enum just makes selecting it
 * for a seat as uniform as selecting our own heuristic AI, for the
 * purpose of running them against each other.
 */
public enum AiPlayerType {
    /** Our own from-scratch heuristic AI - see InProcessAiChannel. */
    SIMPLE_HEURISTIC {
        @Override
        public LobbyPlayer createLobbyPlayer(String seatName) {
            // spectatorChannel is null here too (same reasoning as
            // BatchRunner's other seats) - no human ever watches an
            // evaluation game.
            return new LobbyPlayerRemote(seatName, new InProcessAiChannel(), null);
        }
    },
    /** forge-ai's own bundled AI, used as-is. */
    FORGE_AI {
        @Override
        public LobbyPlayer createLobbyPlayer(String seatName) {
            return new LobbyPlayerAi(seatName, null);
        }
    };

    public abstract LobbyPlayer createLobbyPlayer(String seatName);
}
