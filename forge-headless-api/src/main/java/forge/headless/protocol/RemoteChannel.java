package forge.headless.protocol;

/**
 * Something that can answer a DecisionRequest, blocking the calling
 * (engine) thread until an answer arrives. WebSocketChannel backs a human
 * seat; InProcessAiChannel backs an AI seat with a plain in-JVM method
 * call instead of a network round trip.
 */
public interface RemoteChannel {
    DecisionResponse ask(DecisionRequest request);

    /** Whether this channel actually answers DECLARE_BLOCKERS itself.
     * False by default - RemotePlayerController.declareBlockers falls
     * through to forge-ai's own blocking math for any channel that
     * doesn't override this, since there's no point sending a request
     * nothing will meaningfully answer. WebSocketChannel and
     * RuleBasedAiChannel override this to true. */
    default boolean supportsBlocking() {
        return false;
    }
}
