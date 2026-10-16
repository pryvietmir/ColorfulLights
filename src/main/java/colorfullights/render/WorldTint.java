package colorfullights.render;

import colorfullights.Config;
import colorfullights.debug.Timings;
import colorfullights.light.ColorSampler;
import colorfullights.light.DynamicLights;
import colorfullights.light.LightColors;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;

/**
 * Light color for things drawn outside chunk meshes: entities, items in hand, block entities and particles.
 * <p>
 * Each is tinted by one color, the light color of the block it's in. Colors are cached per block for the
 * current tick, so thousands of particles cost a hash lookup each. Render thread only.
 */
public final class WorldTint {
    private static final int WHITE = 0xFFFFFF;
    private static final Long2IntOpenHashMap CACHE = new Long2IntOpenHashMap();
    private static final float[] SCRATCH = new float[5];
    /** Reused for particles, which are drawn one at a time. */
    private static final TintedVertexConsumer PARTICLE_CONSUMER = new TintedVertexConsumer();

    static {
        CACHE.defaultReturnValue(-1);
    }

    private WorldTint() {
    }

    /** Drops cached colors; called every client tick (lights move, blocks change). */
    public static void clear() {
        CACHE.clear();
    }

    /** Color multiplier (0xRRGGBB) for something at a world position; white if unlit or disabled. */
    public static int at(double x, double y, double z) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !Config.enabled || !Config.tintEntities || !LightColors.isReady()) return WHITE;
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        long key = BlockPos.asLong(bx, by, bz);
        int tint = CACHE.get(key);
        if (tint < 0) {
            long start = Timings.start();
            DynamicLights.Snapshot dynamic = DynamicLights.current();
            float dyn = dynamic.isEmpty() ? 0f : DynamicLights.levelAt(dynamic, bx + 0.5f, by + 0.5f, bz + 0.5f, false);
            // things in the world get light like blocks do: normal reach (walls are respected by world light)
            tint = ColorSampler.nearbyTint(level, bx, by, bz, dyn, SCRATCH);
            CACHE.put(key, tint);
            Timings.end(Timings.Part.WORLD_TINT, start);
        }
        return tint;
    }

    /** Wraps a buffer source so everything drawn into it is tinted with the light color at a position. */
    public static MultiBufferSource wrap(MultiBufferSource buffers, double x, double y, double z) {
        int tint = at(x, y, z);
        return tint == WHITE ? buffers : nextSource().set(buffers, tint);
    }

    /**
     * Wrapped sources are reused instead of allocating a source and a consumer per render type for every entity in
     * every frame. A source is only in use while its object renders, so a ring this deep is never caught up with
     * by nested rendering (passengers, items in hand, block entities on contraptions).
     */
    private static final TintSource[] POOL = new TintSource[256];
    private static int poolNext;

    private static TintSource nextSource() {
        int i = poolNext;
        poolNext = (i + 1) & (POOL.length - 1);
        TintSource source = POOL[i];
        if (source == null) POOL[i] = source = new TintSource();
        return source;
    }

    /** A tinting buffer source with one consumer per render type, kept across uses. */
    private static final class TintSource implements MultiBufferSource {
        private final java.util.IdentityHashMap<net.minecraft.client.renderer.RenderType, TintedVertexConsumer> consumers =
                new java.util.IdentityHashMap<>();
        private MultiBufferSource buffers;
        private int tint;

        TintSource set(MultiBufferSource buffers, int tint) {
            this.buffers = buffers;
            this.tint = tint;
            return this;
        }

        @Override
        public VertexConsumer getBuffer(net.minecraft.client.renderer.RenderType type) {
            TintedVertexConsumer consumer = consumers.get(type);
            if (consumer == null) {
                if (consumers.size() >= 64) consumers.clear(); // render types made per texture: don't grow forever
                consumers.put(type, consumer = new TintedVertexConsumer());
            }
            return consumer.set(buffers, type, tint);
        }
    }

    /** Wraps a particle's vertex consumer; the returned consumer is reused for the next particle. */
    public static VertexConsumer wrapParticle(VertexConsumer consumer, double x, double y, double z) {
        int tint = at(x, y, z);
        return tint == WHITE ? consumer : PARTICLE_CONSUMER.set(consumer, tint);
    }
}
