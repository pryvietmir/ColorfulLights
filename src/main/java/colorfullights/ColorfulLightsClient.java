package colorfullights;

import colorfullights.compat.SableCompat;
import colorfullights.light.EmitterTracker;
import colorfullights.network.DarknessSettings;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/** Client entry point: config, colored and dynamic lighting, rendering darkness. Never loaded on a dedicated server. */
@Mod(value = ColorfulLights.MODID, dist = Dist.CLIENT)
public class ColorfulLightsClient {
    public ColorfulLightsClient(IEventBus modEventBus, ModContainer modContainer) {
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
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> DarknessSettings.reset());

        if (SableCompat.LOADED) SableCompat.registerShaderHooks();
    }
}
