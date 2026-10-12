package colorfullights.light;

import com.mojang.blaze3d.platform.NativeImage;
import colorfullights.ColorfulLights;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Light color (0xRRGGBB) of every light-emitting block state.
 * <p>
 * By default the color is taken from the block's own textures: a weighted average of its pixels in which bright,
 * saturated pixels (flames, crystals, glowing specks) dominate, so a torch glows the color of its flame rather
 * than its stick. Entries in the config override this per block.
 * <p>
 * The table is built on the client thread once resources are loaded (textures must be readable), and read
 * lock-free from chunk meshing threads.
 */
public final class LightColors {
    /** Blocks whose textures don't describe their light (rendered by block entities, invisible, ...). */
    public static final List<String> DEFAULTS = List.of(
            "minecraft:end_portal=#40FFA0",
            "minecraft:end_gateway=#40FFA0",
            "minecraft:light=#FFFFFF"
    );

    private static volatile Map<ResourceLocation, Integer> overrides = Map.of();
    private static volatile int defaultColor = 0xFFFFFF;
    private static volatile boolean useTextures = true;
    /** Color per block state id; null until built. */
    private static volatile int[] byStateId;

    private LightColors() {
    }

    public static void configure(List<? extends String> entries, int defColor, boolean fromTextures) {
        Map<ResourceLocation, Integer> map = new HashMap<>();
        for (String entry : entries) {
            int eq = entry.indexOf('=');
            ResourceLocation id = eq > 0 ? ResourceLocation.tryParse(entry.substring(0, eq).trim()) : null;
            int color = eq > 0 ? parseColor(entry.substring(eq + 1)) : -1;
            if (id == null || color < 0) {
                ColorfulLights.LOGGER.warn("Ignoring invalid light color entry '{}'", entry);
                continue;
            }
            map.put(id, color);
        }
        overrides = map;
        defaultColor = defColor < 0 ? 0xFFFFFF : defColor;
        useTextures = fromTextures;
        remeshRequested = true;
        invalidate();
    }

    /** Drops the table; it is rebuilt on the next client tick. */
    public static void invalidate() {
        byStateId = null;
    }

    public static boolean isReady() {
        return byStateId != null;
    }

    /** @return the light color of the given block state id, or -1 if the table isn't built yet */
    public static int colorOf(int stateId) {
        int[] table = byStateId;
        if (table == null) return -1;
        return stateId >= 0 && stateId < table.length ? table[stateId] : defaultColor;
    }

    /** The last table built, kept to tell whether a rebuild changed any color. */
    private static int[] lastBuilt;
    /** Set when settings that affect tinting changed, so meshes are rebuilt even if the colors didn't. */
    private static volatile boolean remeshRequested;

    /**
     * Builds the table. Must run on the client thread with resources fully loaded.
     *
     * @return whether chunk meshes need rebuilding: a color differs from the previous table, or tint settings
     * changed since the last rebuild
     */
    public static boolean rebuild() {
        long start = System.nanoTime();
        int[] table = new int[Block.BLOCK_STATE_REGISTRY.size()];
        Arrays.fill(table, defaultColor);

        Map<ResourceLocation, Integer> overrides = LightColors.overrides;
        int defaultColor = LightColors.defaultColor;
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        Map<TextureAtlasSprite, double[]> spriteCache = new IdentityHashMap<>();
        int emitters = 0;

        for (Block block : BuiltInRegistries.BLOCK) {
            Integer override = overrides.get(BuiltInRegistries.BLOCK.getKey(block));
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                if (state.getLightEmission() <= 0) continue;
                emitters++;
                int color;
                colorfullights.DirectionalLamps.Lamp lamp = colorfullights.DirectionalLamps.of(state);
                int lampColor = lamp != null ? colorfullights.DirectionalLamps.colorOf(lamp) : -1;
                if (override != null) {
                    color = override;
                } else if (lampColor >= 0) {
                    color = lampColor;
                } else if (useTextures) {
                    color = textureColor(state, dispatcher, spriteCache, defaultColor);
                } else {
                    color = defaultColor;
                }
                table[Block.getId(state)] = color;
            }
        }
        boolean changed = !Arrays.equals(table, lastBuilt) || remeshRequested;
        remeshRequested = false;
        lastBuilt = table;
        byStateId = table;
        EmitterTracker.invalidateCaches();
        ColorfulLights.LOGGER.info("Computed light colors for {} emitting block states ({} textures) in {} ms",
                emitters, spriteCache.size(), (System.nanoTime() - start) / 1_000_000);
        return changed;
    }

    // --- texture sampling ---

    private static int textureColor(BlockState state, BlockRenderDispatcher dispatcher,
                                    Map<TextureAtlasSprite, double[]> cache, int fallback) {
        double r = 0, g = 0, b = 0, w = 0;
        for (TextureAtlasSprite sprite : spritesOf(state, dispatcher)) {
            double[] s = cache.computeIfAbsent(sprite, LightColors::sampleSprite);
            r += s[0];
            g += s[1];
            b += s[2];
            w += s[3];
        }
        double max = Math.max(r, Math.max(g, b));
        if (w <= 1e-6 || max <= 0) return fallback;
        return (int) Math.round(r / max * 255) << 16
                | (int) Math.round(g / max * 255) << 8
                | (int) Math.round(b / max * 255);
    }

    private static List<TextureAtlasSprite> spritesOf(BlockState state, BlockRenderDispatcher dispatcher) {
        List<TextureAtlasSprite> sprites = new ArrayList<>();
        FluidState fluid = state.getFluidState();
        if (state.getRenderShape() == RenderShape.MODEL) {
            BakedModel model = dispatcher.getBlockModel(state);
            RandomSource random = RandomSource.create();
            for (Direction side : SIDES) {
                random.setSeed(42L);
                for (BakedQuad quad : model.getQuads(state, side, random, ModelData.EMPTY, null)) {
                    if (!sprites.contains(quad.getSprite())) sprites.add(quad.getSprite());
                }
            }
        } else if (!fluid.isEmpty()) {
            ResourceLocation still = IClientFluidTypeExtensions.of(fluid).getStillTexture();
            if (still != null) sprites.add(Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(still));
        }
        if (sprites.isEmpty()) sprites.add(dispatcher.getBlockModelShaper().getParticleIcon(state));
        sprites.removeIf(s -> s.contents().name().equals(MissingTextureAtlasSprite.getLocation()));
        return sprites;
    }

    private static final Direction[] SIDES = {null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    /**
     * Weighted color sums {r, g, b, weight} of a sprite. Weight = brightness^6 * (0.1 + saturation^2):
     * dark and gray pixels (wood, stone, metal frames) barely count; glowing, colorful pixels dominate.
     */
    private static double[] sampleSprite(TextureAtlasSprite sprite) {
        double[] sum = new double[4];
        NativeImage image = sprite.contents().getOriginalImage();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int abgr = image.getPixelRGBA(x, y);
                if ((abgr >>> 24) < 128) continue;
                int r = abgr & 0xFF, g = (abgr >> 8) & 0xFF, b = (abgr >> 16) & 0xFF;
                int max = Math.max(r, Math.max(g, b));
                if (max == 0) continue;
                double lum = max / 255.0;
                double sat = (max - Math.min(r, Math.min(g, b))) / (double) max;
                double w = Math.pow(lum, 6) * (0.1 + sat * sat);
                sum[0] += r * w;
                sum[1] += g * w;
                sum[2] += b * w;
                sum[3] += w;
            }
        }
        return sum;
    }

    /** Parses "#RRGGBB" or "RRGGBB"; returns -1 if invalid. */
    public static int parseColor(String s) {
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6) return -1;
        try {
            return Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
