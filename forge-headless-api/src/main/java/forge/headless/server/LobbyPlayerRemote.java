package forge.headless.server;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.headless.protocol.RemoteChannel;
import forge.headless.protocol.WebSocketChannel;

/**
 * A seat controlled remotely - either a human over WebSocket or an AI
 * bridge over HTTP, depending on which RemoteChannel it's given. Mirrors
 * forge-ai's LobbyPlayerAi, just pointed at RemotePlayerController instead.
 */
public class LobbyPlayerRemote extends LobbyPlayer implements IGameEntitiesFactory {

    private final RemoteChannel channel;
    private final WebSocketChannel spectatorChannel;

    public LobbyPlayerRemote(String name, RemoteChannel channel, WebSocketChannel spectatorChannel) {
        super(name);
        this.channel = channel;
        this.spectatorChannel = spectatorChannel;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return new RemotePlayerController(slave.getGame(), slave, this, channel, spectatorChannel);
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        Player p = new Player(getName(), game, id);
        p.setFirstController(new RemotePlayerController(game, p, this, channel, spectatorChannel));
        return p;
    }

    @Override
    public void hear(LobbyPlayer player, String message) { }
}
