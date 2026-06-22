package forge.headless.protocol;

import io.javalin.websocket.WsContext;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Backs a human seat. The engine thread blocks in ask() until the browser
 * sends back a DecisionResponse with a matching request id, which arrives
 * asynchronously via onResponse() from the WebSocket's onMessage handler.
 */
public class WebSocketChannel implements RemoteChannel {

    private final WsContext ctx;
    private final Map<String, CompletableFuture<DecisionResponse>> pending = new ConcurrentHashMap<>();
    private java.util.function.BiConsumer<String, Set<String>> phasePrefsListener;

    public WebSocketChannel(WsContext ctx) {
        this.ctx = ctx;
    }

    public void onResponse(DecisionResponse response) {
        CompletableFuture<DecisionResponse> future = pending.remove(response.id);
        if (future != null) {
            future.complete(response);
        }
    }

    /** Registered by whichever RemotePlayerController actually owns this channel as its decision channel. */
    public void onPhasePrefsChanged(java.util.function.BiConsumer<String, Set<String>> listener) {
        this.phasePrefsListener = listener;
    }

    public void applyPhasePrefs(String forPlayer, Set<String> stopPhases) {
        if (phasePrefsListener != null) {
            phasePrefsListener.accept(forPlayer, stopPhases);
        }
    }

    @Override
    public DecisionResponse ask(DecisionRequest request) {
        CompletableFuture<DecisionResponse> future = new CompletableFuture<>();
        pending.put(request.id, future);
        try {
            ctx.send(request);
            return future.get(10, TimeUnit.MINUTES);
        } catch (Exception e) {
            pending.remove(request.id);
            throw new RuntimeException("Failed to get decision over WebSocket", e);
        }
    }

    public void sendGameOver(String outcome) {
        ctx.send(new DecisionRequest(java.util.UUID.randomUUID().toString(), "GAME_OVER", outcome, null));
        ctx.closeSession();
    }

    /**
     * Fire-and-forget state push, not tied to a decision/response cycle.
     * Lets the human's view stay live while other seats are acting, instead
     * of only updating whenever the human itself is asked something.
     */
    public void pushState(GameStateView state) {
        try {
            ctx.send(new DecisionRequest(java.util.UUID.randomUUID().toString(), "STATE_UPDATE", null, null, state));
        } catch (Exception e) {
            // session likely closed/disconnected - nothing to do
        }
    }
}
