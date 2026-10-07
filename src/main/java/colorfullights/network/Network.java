package colorfullights.network;

import colorfullights.ServerConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * The mod's network channel. It is required, not optional: a client without this mod (or with another protocol)
 * can't join a server that has it, so darkness can't be dropped by removing the mod. Common side.
 */
public final class Network {
    /** Bump when the payloads change; client and server must match. */
    private static final String PROTOCOL = "1";

    private Network() {
    }

    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(PROTOCOL).playToClient(DarknessSettings.TYPE, DarknessSettings.STREAM_CODEC,
                (settings, context) -> DarknessSettings.receive(settings));
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PacketDistributor.sendToPlayer(player, ServerConfig.darkness());
        }
    }

    /** The server config was edited while the server runs: everyone gets the new settings. */
    public static void onServerConfigChanged() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        server.execute(() -> PacketDistributor.sendToAllPlayers(ServerConfig.darkness()));
    }
}
