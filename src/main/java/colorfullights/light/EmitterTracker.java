package colorfullights.light;

import colorfullights.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client-side index of light-emitting blocks, per chunk section.
 * <p>
 * Written only on the client thread; read concurrently by chunk mesh builder threads.
 * Section arrays are never mutated in place (copy-on-write), so readers need no locking.
 * <p>
 * Emitter encoding: bits 0-11 section-local position (x << 8 | y << 4 | z),
 * bits 12-15 light level, bits 32-63 block state id.
 */
public final class EmitterTracker {
    private static final ConcurrentHashMap<Long, long[]> SECTIONS = new ConcurrentHashMap<>();
    /** Bumped on every change so that per-thread sampling caches know to drop stale entries. */
    private static final AtomicInteger VERSION = new AtomicInteger();

    private EmitterTracker() {
    }

    public static long[] get(long sectionKey) {
        return SECTIONS.get(sectionKey);
    }

    public static int version() {
        return VERSION.get();
    }

    public static void invalidateCaches() {
        VERSION.incrementAndGet();
    }

    public static int localPos(long e) {
        return (int) (e & 0xFFF);
    }

    public static int level(long e) {
        return (int) ((e >>> 12) & 0xF);
    }

    public static int stateId(long e) {
        return (int) (e >>> 32);
    }

    private static long encode(int local, BlockState state) {
        return ((long) Block.getId(state) << 32)
                | ((long) state.getLightEmission() << 12)
                | local;
    }

    // --- events ---

    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel().isClientSide()) scanChunk(event.getChunk());
    }

    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!event.getLevel().isClientSide()) return;
        ChunkAccess chunk = event.getChunk();
        ChunkPos cp = chunk.getPos();
        for (int sy = chunk.getMinSection(); sy < chunk.getMaxSection(); sy++) {
            SECTIONS.remove(SectionPos.asLong(cp.x, sy, cp.z));
        }
        invalidateCaches();
    }

    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            SECTIONS.clear();
            DynamicLights.reset();
            invalidateCaches();
        }
    }

    // --- scanning ---

    private static void scanChunk(ChunkAccess chunk) {
        ChunkPos cp = chunk.getPos();
        LevelChunkSection[] sections = chunk.getSections();
        long[] buf = new long[64];
        for (int i = 0; i < sections.length; i++) {
            long key = SectionPos.asLong(cp.x, chunk.getSectionYFromSectionIndex(i), cp.z);
            LevelChunkSection section = sections[i];
            if (section.hasOnlyAir() || !section.maybeHas(s -> s.getLightEmission() > 0)) {
                SECTIONS.remove(key);
                continue;
            }
            int n = 0;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.getLightEmission() <= 0) continue;
                        if (n == buf.length) buf = Arrays.copyOf(buf, n * 2);
                        buf[n++] = encode(x << 8 | y << 4 | z, state);
                    }
                }
            }
            if (n == 0) SECTIONS.remove(key);
            else SECTIONS.put(key, Arrays.copyOf(buf, n));
        }
        invalidateCaches();
    }

    /** Called from the client thread whenever a block changes in a client level. */
    public static void onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState) {
        DynamicLights.onBlockChanged(pos, oldState, newState);
        if (SableCompat.LOADED) SableCompat.onBlockChanged(pos);
        boolean wasEmitter = oldState.getLightEmission() > 0;
        boolean isEmitter = newState.getLightEmission() > 0;
        if (!wasEmitter && !isEmitter) return;

        long key = SectionPos.asLong(pos);
        int local = (pos.getX() & 15) << 8 | (pos.getY() & 15) << 4 | (pos.getZ() & 15);
        SECTIONS.compute(key, (k, old) -> {
            long[] base = old == null ? new long[0] : old;
            long[] next = new long[base.length + 1];
            int n = 0;
            for (long e : base) {
                if (localPos(e) != local) next[n++] = e;
            }
            if (isEmitter) next[n++] = encode(local, newState);
            return n == 0 ? null : Arrays.copyOf(next, n);
        });
        invalidateCaches();
    }
}
