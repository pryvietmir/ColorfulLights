package colorfullights;

import colorfullights.beam.BeamLightBlock;
import colorfullights.beam.BeamLights;
import colorfullights.network.Network;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

/**
 * Colorful Lights: tints block light by the color of nearby light sources, adds dynamic and spot lights, and makes
 * unlit places truly dark.
 * <p>
 * Rendering is client side (see {@link ColorfulLightsClient}); the color is baked into terrain vertex colors when
 * chunks are meshed, both by the vanilla section compiler and by Sodium. The common part decides darkness: its
 * settings are a server config sent to the players, and the Nether dimension types have no ambient light. Beams of
 * directional lamps ({@link DirectionalLamps}) get real light on the server. A server with this mod only lets in
 * clients that have it too.
 */
@Mod(ColorfulLights.MODID)
public class ColorfulLights {
    public static final String MODID = "colorful_lights";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    /** Invisible light where a directional lamp's beam hits. */
    public static final DeferredBlock<BeamLightBlock> BEAM_LIGHT = BLOCKS.register("beam_light",
            () -> new BeamLightBlock(BeamLightBlock.defaultProperties()));

    public ColorfulLights(IEventBus modEventBus, ModContainer modContainer) {
        BLOCKS.register(modEventBus);
        BeamLights.init(BEAM_LIGHT);
        modEventBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(DirectionalLamps::applyAmbientLight));
        modContainer.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
        modEventBus.addListener(Network::onRegisterPayloads);
        modEventBus.addListener((ModConfigEvent.Reloading event) -> {
            if (event.getConfig().getSpec() == ServerConfig.SPEC) Network.onServerConfigChanged();
        });
        NeoForge.EVENT_BUS.addListener(Network::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(BeamLights::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(BeamLights::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(BeamLights::onLevelTick);
    }
}
