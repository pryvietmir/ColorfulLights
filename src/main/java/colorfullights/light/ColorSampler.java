package colorfullights.light;

import colorfullights.Config;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;

/**
 * Computes the colored-light tint for terrain vertices.
 * <p>
 * The <em>hue</em> comes from nearby emitters (placed blocks and {@link DynamicLights}): a weighted average
 * of their colors, where the weight falls off with Manhattan distance the same way vanilla block light does. The <em>strength</em> of the tint comes from
 * the vertex's actual block light value, so walls still block light (no light, no tint) and sky light washes
 * the color out outdoors.
 * <p>
 * Called from chunk meshing threads; all state is thread-confined or read from immutable snapshots.
 */
public final class ColorSampler {
    private static final int WHITE = 0xFFFFFF;
    private static final int CACHE_LIMIT = 1 << 16;

    private static final ThreadLocal<Cache> CACHE = ThreadLocal.withInitial(Cache::new);

    private static final class Cache {
        final Long2IntOpenHashMap tints = new Long2IntOpenHashMap();
        final float[] sum = new float[4];
        long version = -1;
    }

    private ColorSampler() {
    }

    /** Maximum extra light used to make up for the darkening caused by a colored tint. */
    private static final float MAX_COMPENSATION = 2.5f;

    /**
     * Shades a vertex: tints its color and brightens its block light.
     * <p>
     * Vertex colors can only darken a texture, so brightness comes from raising the block light coordinate
     * (which the lightmap turns into brightness): by {@link Config#brightness}, and additionally by as much as
     * the tint darkens the surface, so blue or green light isn't dimmer than white light.
     *
     * @param x,y,z  world-space vertex position
     * @param color  vertex color with red in bits 16-23 and blue in bits 0-7 (ARGB layout; alpha is kept)
     * @param light  packed lightmap coordinates of the vertex (block in the low 16 bits, sky in the high 16)
     * @return the new light in the high 32 bits and the new color in the low 32 bits; see {@link #color}/{@link #light}
     */
    public static long shadeARGB(float x, float y, float z, int color, int light) {
        if (!Config.enabled || !LightColors.isReady()) return pack(color, light);

        int blockRaw = light & 0xFFFF;
        DynamicLights.Snapshot dynamic = DynamicLights.current();
        if (!dynamic.isEmpty() && !DynamicLights.inShader()) {
            // Without the Sodium shader, dynamic lights are baked into the mesh: raise the vertex light to them
            blockRaw = Math.max(blockRaw, Math.round(DynamicLights.levelAt(dynamic, x, y, z, true) * 16f));
        }
        float block = Math.min(blockRaw / 240f, 1f);
        if (block <= 0f) return pack(color, light);

        float boost = Config.brightness;
        int tint = latticeTint(Math.round(x), Math.round(y), Math.round(z));
        if (tint != WHITE) {
            float sky = Math.min(((light >>> 16) & 0xFFFF) / 240f, 1f);
            // Full color from block light ~6 up, so the colored area spans most of the light's reach
            float strength = Config.intensity * Math.min(1f, block * 2.5f) * (1f - Config.skyReduction * sky);
            if (strength > 0f) {
                float mr = multiplier(tint >> 16, strength);
                float mg = multiplier(tint >> 8, strength);
                float mb = multiplier(tint, strength);
                int r = Math.round(((color >> 16) & 0xFF) * mr);
                int g = Math.round(((color >> 8) & 0xFF) * mg);
                int b = Math.round((color & 0xFF) * mb);
                color = (color & 0xFF000000) | r << 16 | g << 8 | b;
                boost *= Math.min(MAX_COMPENSATION, 1f / Math.max(keptBrightness(mr, mg, mb), 1e-3f));
            }
        }
        int boosted = Math.min(240, Math.round(blockRaw * boost));
        return pack(color, (light & 0xFFFF0000) | boosted);
    }

    /** Same as {@link #shadeARGB} for colors with red in the low byte (ABGR, as Sodium stores them). */
    public static long shadeABGR(float x, float y, float z, int color, int light) {
        long shaded = shadeARGB(x, y, z, swapRB(color), light);
        return pack(swapRB(color(shaded)), light(shaded));
    }

    public static int color(long shaded) {
        return (int) shaded;
    }

    public static int light(long shaded) {
        return (int) (shaded >>> 32);
    }

    private static long pack(int color, int light) {
        return (long) light << 32 | (color & 0xFFFFFFFFL);
    }

    private static int swapRB(int c) {
        return (c & 0xFF00FF00) | (c & 0xFF) << 16 | (c >> 16) & 0xFF;
    }

    /**
     * How bright a surface stays under a color multiplier: halfway between the plain average and perceived
     * luminance, so blue and green light (which look darker than their channel values) get more compensation.
     */
    private static float keptBrightness(float r, float g, float b) {
        return 0.5f * (r + g + b) / 3f + 0.5f * (0.2126f * r + 0.7152f * g + 0.0722f * b);
    }

    private static float multiplier(int tint, float strength) {
        return 1f + ((tint & 0xFF) / 255f - 1f) * strength;
    }

    /** Normalized light color (brightest channel = 255) at a block-corner lattice point, or WHITE if none. */
    private static int latticeTint(int lx, int ly, int lz) {
        Cache cache = CACHE.get();
        boolean withDynamic = !DynamicLights.inShader();
        // Terrain tint depends on placed emitters, and on dynamic lights only while they're baked into meshes.
        long version = (long) EmitterTracker.version() << 32 | (withDynamic ? DynamicLights.version() & 0xFFFFFFFFL : 0L);
        if (cache.version != version || cache.tints.size() > CACHE_LIMIT) {
            cache.tints.clear();
            cache.version = version;
        }
        long key = BlockPos.asLong(lx, ly, lz);
        int tint = cache.tints.getOrDefault(key, -1);
        if (tint < 0) {
            float[] sum = cache.sum;
            sum[0] = sum[1] = sum[2] = sum[3] = 0f;
            accumulateNearby(lx, ly, lz, sum);
            if (withDynamic) accumulateDynamic(lx, ly, lz, true, sum);
            tint = sum[3] <= 0f ? WHITE : saturate(sum[0], sum[1], sum[2]);
            cache.tints.put(key, tint);
        }
        return tint;
    }

    /** Adds placed emitters around a lattice point to a weighted color sum {r, g, b, w}. */
    private static void accumulateNearby(int lx, int ly, int lz, float[] sum) {
        // Light reaches at most 15 blocks, so the emitter's section is within one section of the point
        // (a lattice point on the far edge of a section still sees at most 16 blocks away).
        int scx = SectionPos.blockToSectionCoord(lx);
        int scy = SectionPos.blockToSectionCoord(ly);
        int scz = SectionPos.blockToSectionCoord(lz);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    long[] emitters = EmitterTracker.get(SectionPos.asLong(scx + dx, scy + dy, scz + dz));
                    if (emitters != null) {
                        accumulate(emitters, (scx + dx) << 4, (scy + dy) << 4, (scz + dz) << 4, lx, ly, lz, sum);
                    }
                }
            }
        }
    }

    /** Adds one section's emitters (section origin ox, oy, oz) to a weighted color sum at a position. */
    private static void accumulate(long[] emitters, int ox, int oy, int oz, float x, float y, float z, float[] sum) {
        for (long e : emitters) {
            int p = EmitterTracker.localPos(e);
            // Manhattan distance to the emitter block's center, like vanilla light propagation
            float dist = Math.abs(x - (ox + (p >> 8) + 0.5f))
                    + Math.abs(y - (oy + ((p >> 4) & 15) + 0.5f))
                    + Math.abs(z - (oz + (p & 15) + 0.5f));
            float v = EmitterTracker.level(e) + 0.5f - dist;
            if (v <= 0f) continue;
            int c = LightColors.colorOf(EmitterTracker.stateId(e));
            if (c < 0) continue;
            float w = v * v;
            sum[0] += ((c >> 16) & 0xFF) * w;
            sum[1] += ((c >> 8) & 0xFF) * w;
            sum[2] += (c & 0xFF) * w;
            sum[3] += w;
        }
    }

    private static void accumulateDynamic(float x, float y, float z, boolean corner, float[] sum) {
        DynamicLights.Snapshot dynamic = DynamicLights.current();
        if (!dynamic.isEmpty()) DynamicLights.accumulate(dynamic, x, y, z, corner, sum);
    }

    /** Normalizes a color so its brightest channel is 255, then boosts saturation by {@link Config#saturation}. */
    public static int saturate(int rgb) {
        return saturate((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    private static int saturate(float sr, float sg, float sb) {
        float max = Math.max(sr, Math.max(sg, sb));
        if (max <= 0f) return WHITE;
        float r = sr / max, g = sg / max, b = sb / max;

        // Boost saturation while keeping the hue: with the brightest channel at 1, every channel is
        // 1 - s * d where s is the HSV saturation, so scaling s moves the color away from white.
        float s = 1f - Math.min(r, Math.min(g, b));
        if (s > 1e-4f) {
            float k = Math.min(1f, s * Config.saturation) / s;
            r = 1f - (1f - r) * k;
            g = 1f - (1f - g) * k;
            b = 1f - (1f - b) * k;
        }
        return Math.round(r * 255f) << 16 | Math.round(g * 255f) << 8 | Math.round(b * 255f);
    }

    // --- whole objects (contraptions, ships) ---

    /**
     * Placed emitters that can reach a box, gathered once for many lookups inside it.
     * <p>
     * Moving objects get light from further away than terrain: an emitter's reach is multiplied by
     * {@link Config#objectLightRange}, scaled by how open to the sky the emitter is. There are no wall checks for
     * objects, so a lamp shut inside a building keeps its normal reach instead of shining through the roof.
     */
    public static final class Emitters {
        private final FloatArrayList data = new FloatArrayList(); // x, y, z, level, falloff (1/k) per emitter
        private final IntArrayList colors = new IntArrayList();

        /**
         * Emitters that can light any point of the given block box (inclusive), with reach multiplied by up to
         * {@code range} for emitters open to the sky. Reads the level.
         */
        public static Emitters around(Level level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, float range) {
            Emitters set = new Emitters();
            range = Math.max(1f, range);
            int margin = (int) Math.ceil(15 * range) + 1;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int sx = (minX - margin) >> 4; sx <= (maxX + margin) >> 4; sx++)
                for (int sy = (minY - margin) >> 4; sy <= (maxY + margin) >> 4; sy++)
                    for (int sz = (minZ - margin) >> 4; sz <= (maxZ + margin) >> 4; sz++) {
                        long[] emitters = EmitterTracker.get(SectionPos.asLong(sx, sy, sz));
                        if (emitters == null) continue;
                        for (long e : emitters) {
                            int c = LightColors.colorOf(EmitterTracker.stateId(e));
                            if (c < 0) continue;
                            int p = EmitterTracker.localPos(e);
                            int x = (sx << 4) + (p >> 8), y = (sy << 4) + ((p >> 4) & 15), z = (sz << 4) + (p & 15);
                            float k = range <= 1f ? 1f
                                    : 1f + (range - 1f) * level.getBrightness(LightLayer.SKY, pos.set(x, y, z)) / 15f;
                            set.data.add(x + 0.5f);
                            set.data.add(y + 0.5f);
                            set.data.add(z + 0.5f);
                            set.data.add(EmitterTracker.level(e));
                            set.data.add(1f / k);
                            set.colors.add(c);
                        }
                    }
            return set;
        }

        /** Adds weighted colors to sum[0..3] and the brightest light level reaching the point to sum[4]. */
        void accumulate(float x, float y, float z, float[] sum) {
            float[] d = data.elements();
            for (int i = 0, n = colors.size(); i < n; i++) {
                int o = i * 5;
                float dx = x - d[o], dy = y - d[o + 1], dz = z - d[o + 2];
                float reach = d[o + 3] / d[o + 4];
                float dist2 = dx * dx + dy * dy + dz * dz;
                if (dist2 >= reach * reach) continue;
                float lv = d[o + 3] - (float) Math.sqrt(dist2) * d[o + 4];
                if (lv > sum[4]) sum[4] = lv;
                int c = colors.getInt(i);
                float w = (lv + 0.5f) * (lv + 0.5f);
                sum[0] += ((c >> 16) & 0xFF) * w;
                sum[1] += ((c >> 8) & 0xFF) * w;
                sum[2] += (c & 0xFF) * w;
                sum[3] += w;
            }
        }
    }

    /**
     * Color multiplier (0xRRGGBB, white = unchanged) for an object at a world position: the tint of placed and
     * dynamic lights there, weighted by how much light reaches it. Safe to call from any thread.
     */
    public static int objectTint(Level level, double x, double y, double z) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        DynamicLights.Snapshot dynamic = DynamicLights.current();
        float dyn = dynamic.isEmpty() ? 0f : DynamicLights.levelAt(dynamic, (float) x, (float) y, (float) z, false);
        return objectTint(level, x, y, z, Emitters.around(level, bx, by, bz, bx, by, bz, Config.objectLightRange), dyn, new float[5]);
    }

    /**
     * {@link #objectTint(Level, double, double, double)} with precomputed nearby emitters and dynamic light level
     * {@code dyn} at the position. {@code scratch} is a reusable float[5]; on return scratch[4] holds the block
     * light level (0..15) the object gets there: the world's, the extended reach of emitters, or dynamic light.
     */
    public static int objectTint(Level level, double x, double y, double z, Emitters emitters, float dyn, float[] scratch) {
        scratch[0] = scratch[1] = scratch[2] = scratch[3] = scratch[4] = 0f;
        if (!Config.enabled || !LightColors.isReady()) return WHITE;
        BlockPos pos = BlockPos.containing(x, y, z);
        float px = (float) x, py = (float) y, pz = (float) z;
        emitters.accumulate(px, py, pz, scratch);
        float blockLevel = Math.max(Math.max(level.getBrightness(LightLayer.BLOCK, pos), scratch[4]), dyn);
        scratch[4] = blockLevel;
        if (dyn > 0f) accumulateDynamic(px, py, pz, false, scratch);
        if (blockLevel <= 0f || scratch[3] <= 0f) return WHITE;
        int tint = saturate(scratch[0], scratch[1], scratch[2]);

        // Objects are recomputed every tick, so unlike baked terrain they can tell day from night:
        // sky light only washes the color out while the sky is actually bright.
        float daylight = 1f - level.getSkyDarken() / 11f;
        float sky = level.getBrightness(LightLayer.SKY, pos) / 15f * daylight;
        float strength = Config.intensity * Math.min(1f, blockLevel / 15f * 2.5f) * (1f - Config.skyReduction * sky);
        if (strength <= 0f) return WHITE;
        int r = Math.round(255f * multiplier(tint >> 16, strength));
        int g = Math.round(255f * multiplier(tint >> 8, strength));
        int b = Math.round(255f * multiplier(tint, strength));
        return r << 16 | g << 8 | b;
    }
}
