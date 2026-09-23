package colorfullights;

import com.mojang.logging.LogUtils;
import colorfullights.compat.SableCompat;
import colorfullights.light.EmitterTracker;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Colorful Lights: tints block light by the color of nearby light sources.
 * Purely client side; the color is baked into terrain vertex colors when chunks are meshed,
 * both by the vanilla section compiler and by Sodium. Light colors come from the emitters' own textures.
 */
@Mod(value = ColorfulLights.MODID, dist = Dist.CLIENT)
public class ColorfulLights {
    public static final String MODID = "colorful_lights";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ColorfulLights(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modEventBus.addListener(Config::onLoad);
        modEventBus.addListener(Config::onReload);
        modEventBus.addListener(ClientEvents::onRegisterReloadListeners);

        NeoForge.EVENT_BUS.addListener(ClientEvents::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClientEvents::onFrameStart);
        NeoForge.EVENT_BUS.addListener(EmitterTracker::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(EmitterTracker::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(EmitterTracker::onLevelUnload);

        if (SableCompat.LOADED) SableCompat.registerShaderHooks();
    }
}
