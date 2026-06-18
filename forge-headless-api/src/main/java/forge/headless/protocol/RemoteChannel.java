package forge.headless.protocol;

/**
 * Something that can answer a DecisionRequest, blocking the calling
 * (engine) thread until an answer arrives. WebSocketChannel backs a human
 * seat; HttpChannel backs an AI seat. Same protocol, different transport.
 */
public interface RemoteChannel {
    DecisionResponse ask(DecisionRequest request);
}
