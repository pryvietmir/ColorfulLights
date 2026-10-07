package colorfullights;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Colorful Lights: tints block light by the color of nearby light sources, adds dynamic and spot lights, and makes
 * unlit places truly dark.
 * <p>
 * Almost everything is client side (see {@link ColorfulLightsClient}); the color is baked into terrain vertex colors
 * when chunks are meshed, both by the vanilla section compiler and by Sodium. The common part is data only: the
 * Nether dimension types without ambient light and the item tags. The mod may be installed on either side alone.
 */
@Mod(ColorfulLights.MODID)
public class ColorfulLights {
    public static final String MODID = "colorful_lights";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ColorfulLights() {
    }
}
