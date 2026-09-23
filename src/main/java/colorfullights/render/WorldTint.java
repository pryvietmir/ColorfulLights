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
        return tint == WHITE ? buffers : type -> new TintedVertexConsumer().set(buffers.getBuffer(type), tint);
    }

    /** Wraps a particle's vertex consumer; the returned consumer is reused for the next particle. */
    public static VertexConsumer wrapParticle(VertexConsumer consumer, double x, double y, double z) {
        int tint = at(x, y, z);
        return tint == WHITE ? consumer : PARTICLE_CONSUMER.set(consumer, tint);
    }
}
