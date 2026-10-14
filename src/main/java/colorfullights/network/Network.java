package colorfullights.network;

import colorfullights.lamp.LampInteraction;
import colorfullights.lamp.LampSettings;
import colorfullights.lamp.LampSettingsPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * The mod's network channel: settings of placed lamps (colors, beam distance, focus) for the clients. It is
 * required, not optional: client and server must both have the mod with the same protocol. Common side.
 */
public final class Network {
    /** Bump when the payloads change; client and server must match. */
    private static final String PROTOCOL = "3";

    private Network() {
    }

    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(PROTOCOL)
                .playToClient(LampSettingsPayload.TYPE, LampSettingsPayload.STREAM_CODEC,
                        (settings, context) -> LampSettings.receive(settings));
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            LampInteraction.sendAll(player);
        }
    }
}
