package colorfullights;

import colorfullights.network.Network;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Colorful Lights: tints block light by the color of nearby light sources, adds dynamic and spot lights, and makes
 * unlit places truly dark.
 * <p>
 * Rendering is client side (see {@link ColorfulLightsClient}); the color is baked into terrain vertex colors when
 * chunks are meshed, both by the vanilla section compiler and by Sodium. The common part decides darkness: its
 * settings are a server config sent to the players, and the Nether dimension types have no ambient light. A server
 * with this mod only lets in clients that have it too.
 */
@Mod(ColorfulLights.MODID)
public class ColorfulLights {
    public static final String MODID = "colorful_lights";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ColorfulLights(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
        modEventBus.addListener(Network::onRegisterPayloads);
        modEventBus.addListener((ModConfigEvent.Reloading event) -> {
            if (event.getConfig().getSpec() == ServerConfig.SPEC) Network.onServerConfigChanged();
        });
        NeoForge.EVENT_BUS.addListener(Network::onPlayerLoggedIn);
    }
}
