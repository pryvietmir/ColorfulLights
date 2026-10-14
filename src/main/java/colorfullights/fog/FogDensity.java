package colorfullights.fog;

import colorfullights.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;

/**
 * How hazy the air around the camera is, 0 (perfectly clear) to 1 (thick fog): how much light the air scatters
 * towards the eye, e.g. for the visible beams of lamps. Client only.
 * <p>
 * A base haze from the config ({@code beamBaseFog}), plus night, plus being underground, plus the weather: with
 * Harsh World installed its weather fog (which already covers rain, storms, blizzards and caves), otherwise rain,
 * thunder and snowfall in cold biomes. This mod only reads fog; it never changes how far the game's fog reaches.
 */
public final class FogDensity {
    /** Extra haze at full rain, at full thunder, at night, in falling snow, and underground (no sky above). */
    private static final float RAIN = 0.35f, THUNDER = 0.15f, NIGHT = 0.12f, SNOW = 0.2f, UNDERGROUND = 0.2f;
    /** Share of the distance to the target value covered per tick (sudden changes, e.g. entering a cave, ease in). */
    private static final float EASE = 0.05f;

    private static float target, current, previous;

    private FogDensity() {
    }

    /** The haze for the current frame, 0..1. Render thread. */
    public static float get() {
        float pt = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        return Mth.lerp(pt, previous, current);
    }

    /** Updates the haze from the weather and where the camera is. Client tick. */
    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null) {
            target = current = previous = Config.beamBaseFog;
            return;
        }
        target = compute(mc, level);
        previous = current;
        current += (target - current) * EASE;
    }

    private static float compute(Minecraft mc, ClientLevel level) {
        float density = Config.beamBaseFog;
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        BlockPos pos = BlockPos.containing(cam);

        boolean sky = level.dimensionType().hasSkyLight();
        float weather = HarshWorldFog.density();
        if (weather >= 0f) density += weather; // Harsh World decides the weather fog, in every dimension
        if (sky) {
            float rain = level.getRainLevel(1f), thunder = level.getThunderLevel(1f);
            if (weather < 0f && rain > 0f) {
                // where it snows, the flakes scatter more light than rain drops
                Biome.Precipitation precipitation = level.getBiome(pos).value().getPrecipitationAt(pos);
                density += rain * (precipitation == Biome.Precipitation.SNOW ? RAIN + SNOW : precipitation == Biome.Precipitation.RAIN ? RAIN : 0f);
            }
            if (weather < 0f) density += thunder * THUNDER;
            density += NIGHT * (1f - daylight(level));
            // under a roof or deep in a cave: the light of the sky doesn't reach the camera
            int skyLight = level.getBrightness(LightLayer.SKY, pos);
            density += UNDERGROUND * (1f - skyLight / 15f);
        } else {
            density += UNDERGROUND; // the Nether and other dimensions without a sky are stuffy
        }
        return Mth.clamp(density, 0f, 1f);
    }

    /**
     * Harsh World's weather fog ({@code com.pack.harshworld.weather.fog.WeatherFog.clientLevel().density()}, 0..1),
     * read reflectively so this mod doesn't need it.
     */
    private static final class HarshWorldFog {
        private static final java.lang.invoke.MethodHandle CLIENT_LEVEL, DENSITY;

        static {
            java.lang.invoke.MethodHandle level = null, density = null;
            if (net.neoforged.fml.ModList.get().isLoaded("harsh_world")) {
                try {
                    Class<?> api = Class.forName("com.pack.harshworld.weather.fog.WeatherFog");
                    Class<?> state = Class.forName("com.pack.harshworld.weather.fog.WeatherFog$State");
                    var lookup = java.lang.invoke.MethodHandles.publicLookup();
                    level = lookup.findStatic(api, "clientLevel", java.lang.invoke.MethodType.methodType(state));
                    density = lookup.findVirtual(state, "density", java.lang.invoke.MethodType.methodType(float.class));
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    colorfullights.ColorfulLights.LOGGER.warn("Harsh World's fog API not found; beams use their own weather haze", e);
                    level = density = null;
                }
            }
            CLIENT_LEVEL = level;
            DENSITY = density;
        }

        /** The weather fog density 0..1, or -1 without Harsh World. */
        static float density() {
            if (CLIENT_LEVEL == null) return -1f;
            try {
                return Mth.clamp((float) DENSITY.invoke(CLIENT_LEVEL.invoke()), 0f, 1f);
            } catch (Throwable t) {
                return -1f;
            }
        }
    }

    /** How bright the day is, 0 (night) to 1 (noon), like vanilla's sky darkening without the weather. */
    private static float daylight(ClientLevel level) {
        float g = 1f - (Mth.cos(level.getTimeOfDay(1f) * ((float) Math.PI * 2f)) * 2f + 0.2f);
        return 1f - Mth.clamp(g, 0f, 1f);
    }
}
