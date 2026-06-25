package forge.headless.protocol;

/**
 * Something that can answer a DecisionRequest, blocking the calling
 * (engine) thread until an answer arrives. WebSocketChannel backs a human
 * seat; InProcessAiChannel backs an AI seat with a plain in-JVM method
 * call instead of a network round trip.
 */
public interface RemoteChannel {
    DecisionResponse ask(DecisionRequest request);
}
