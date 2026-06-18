package forge.headless.server;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.headless.protocol.RemoteChannel;

/**
 * A seat controlled remotely - either a human over WebSocket or an AI
 * bridge over HTTP, depending on which RemoteChannel it's given. Mirrors
 * forge-ai's LobbyPlayerAi, just pointed at RemotePlayerController instead.
 */
public class LobbyPlayerRemote extends LobbyPlayer implements IGameEntitiesFactory {

    private final RemoteChannel channel;

    public LobbyPlayerRemote(String name, RemoteChannel channel) {
        super(name);
        this.channel = channel;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return new RemotePlayerController(slave.getGame(), slave, this, channel);
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        Player p = new Player(getName(), game, id);
        p.setFirstController(new RemotePlayerController(game, p, this, channel));
        return p;
    }

    @Override
    public void hear(LobbyPlayer player, String message) { }
}
