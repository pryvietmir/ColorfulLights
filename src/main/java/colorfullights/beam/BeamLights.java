package colorfullights.beam;

import colorfullights.DirectionalLamps;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * Real light for the beams of {@link DirectionalLamps} placed in the world (server side): a few rays of each lamp's
 * cone are cast through the world, and an invisible {@link BeamLightBlock} goes into the air cell in front of each
 * block they hit, brighter the closer it is. Beam lights are only placed into air and only ever removed by the lamp
 * that placed them (or by their random tick once no lamp claims them).
 * <p>
 * Lamps are found when chunks load and when blocks change; their beams are updated every {@link #INTERVAL} ticks.
 * Lamps on moving contraptions or ships only get the client-side beam.
 */
public final class BeamLights {
    private static final int INTERVAL = 10;
    /** At most this many lamps per level are updated per interval. */
    private static final int MAX_LAMPS = 128;
    /** Rays: the axis, and this many around it halfway between the inner and outer angle. */
    private static final int RING = 6;
    /** Hits closer to the lamp than this get no beam light: the lamp's own light covers them. */
    private static final double MIN_DISTANCE = 3;
    /** Block update flags: tell clients, but no neighbor shape updates. */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static Supplier<? extends Block> beamLight;

    private static final class LevelData {
        final LongOpenHashSet lamps = new LongOpenHashSet();
        /** Beam light positions per lamp. */
        final Long2ObjectOpenHashMap<long[]> beams = new Long2ObjectOpenHashMap<>();
        /** How many lamps claim each beam light position. */
        final Long2IntOpenHashMap claims = new Long2IntOpenHashMap();
        /** Chunks loaded since the last tick (the load event may come from another thread). */
        final Queue<ChunkPos> loaded = new ConcurrentLinkedQueue<>();
        /** Lamps whose beam must be updated at the next tick (placed or turned). */
        final LongOpenHashSet urgent = new LongOpenHashSet();
    }

    private static final Map<ServerLevel, LevelData> LEVELS = new ConcurrentHashMap<>();

    private BeamLights() {
    }

    public static void init(Supplier<? extends Block> block) {
        beamLight = block;
    }

    private static LevelData data(ServerLevel level) {
        return LEVELS.computeIfAbsent(level, l -> new LevelData());
    }

    static boolean isClaimed(ServerLevel level, BlockPos pos) {
        LevelData data = LEVELS.get(level);
        return data != null && data.claims.get(pos.asLong()) > 0;
    }

    // --- events ---

    public static void onChunkLoad(ChunkEvent.Load event) {
        if (DirectionalLamps.isEmpty()) return;
        if (event.getLevel() instanceof ServerLevel level) data(level).loaded.add(event.getChunk().getPos());
    }

    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) LEVELS.remove(level);
    }

    /** A block changed in a server level (from LevelChunk.setBlockState). Server thread. */
    public static void onBlockChanged(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        boolean was = DirectionalLamps.of(oldState) != null, is = DirectionalLamps.of(newState) != null;
        if (!was && !is) return;
        LevelData data = data(level);
        long key = pos.asLong();
        if (is) {
            data.lamps.add(key);
            data.urgent.add(key);
        } else {
            data.lamps.remove(key);
            data.urgent.remove(key);
            clear(level, data, key);
        }
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        LevelData data = LEVELS.get(level);
        if (data == null) return;

        ChunkPos cp;
        while ((cp = data.loaded.poll()) != null) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x, cp.z);
            if (chunk != null) scan(chunk, data);
        }

        if (!data.urgent.isEmpty()) {
            long[] urgent = data.urgent.toLongArray();
            data.urgent.clear();
            for (long lamp : urgent) update(level, data, lamp);
        }
        if (level.getGameTime() % INTERVAL != 0 || data.lamps.isEmpty()) return;

        LongArrayList gone = new LongArrayList();
        int n = 0;
        for (long lamp : data.lamps.toLongArray()) {
            BlockPos pos = BlockPos.of(lamp);
            if (!level.isLoaded(pos)) {
                // the lamp's chunk unloaded: its beam lights expire unless it comes back
                gone.add(lamp);
                continue;
            }
            if (n++ < MAX_LAMPS) update(level, data, lamp);
        }
        for (long lamp : gone) {
            data.lamps.remove(lamp);
            long[] beams = data.beams.remove(lamp);
            if (beams != null) for (long b : beams) unclaim(data, b);
        }
    }

    // --- lamps ---

    private static void scan(LevelChunk chunk, LevelData data) {
        LevelChunkSection[] sections = chunk.getSections();
        ChunkPos cp = chunk.getPos();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section.hasOnlyAir() || !section.maybeHas(s -> DirectionalLamps.of(s) != null)) continue;
            int sy = chunk.getSectionYFromSectionIndex(i);
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        if (DirectionalLamps.of(section.getBlockState(x, y, z)) == null) continue;
                        long key = BlockPos.asLong(cp.getMinBlockX() + x, SectionPos.sectionToBlockCoord(sy) + y, cp.getMinBlockZ() + z);
                        data.lamps.add(key);
                        data.urgent.add(key);
                    }
        }
    }

    private static void update(ServerLevel level, LevelData data, long lampKey) {
        BlockPos lampPos = BlockPos.of(lampKey);
        BlockState state = level.getBlockState(lampPos);
        DirectionalLamps.Lamp lamp = DirectionalLamps.of(state);
        if (lamp == null) {
            data.lamps.remove(lampKey);
            clear(level, data, lampKey);
            return;
        }

        Direction facing = DirectionalLamps.direction(lamp, state);
        Vec3 dir = Vec3.atLowerCornerOf(facing.getNormal());
        Vec3 origin = Vec3.atCenterOf(lampPos).add(dir.scale(0.6));
        Vec3 right = Vec3.atLowerCornerOf(facing.getAxis() == Direction.Axis.Y ? Direction.EAST.getNormal()
                : facing.getClockWise().getNormal());
        Vec3 up = right.cross(dir);
        double spread = Math.tan(Math.toRadians((lamp.inner() + lamp.outer()) * 0.5));

        Long2IntOpenHashMap wanted = new Long2IntOpenHashMap();
        for (int r = 0; r <= RING; r++) {
            Vec3 ray = dir;
            if (r > 0) {
                double a = 2 * Math.PI * (r - 1) / RING;
                ray = dir.add(right.scale(Math.cos(a) * spread)).add(up.scale(Math.sin(a) * spread)).normalize();
            }
            Vec3 end = origin.add(ray.scale(lamp.range()));
            BlockHitResult hit = level.clip(new ClipContext(origin, end, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE,
                    CollisionContext.empty()));
            if (hit.getType() == HitResult.Type.MISS) continue;
            double d = origin.distanceTo(hit.getLocation());
            if (d < MIN_DISTANCE) continue;
            int lv = (int) Math.round(lamp.level() * (1 - 0.6 * d / lamp.range()));
            lv = Math.max(1, Math.min(15, lv));
            long at = hit.getBlockPos().relative(hit.getDirection()).asLong();
            if (lv > wanted.get(at)) wanted.put(at, lv);
        }

        long[] old = data.beams.get(lampKey);
        if (old != null) {
            for (long b : old) {
                if (!wanted.containsKey(b) && unclaim(data, b)) remove(level, b);
            }
        }
        LongOpenHashSet had = new LongOpenHashSet();
        if (old != null) for (long b : old) had.add(b);
        for (Long2IntOpenHashMap.Entry e : wanted.long2IntEntrySet()) {
            long b = e.getLongKey();
            if (!had.contains(b)) data.claims.addTo(b, 1);
            place(level, BlockPos.of(b), e.getIntValue());
        }
        if (wanted.isEmpty()) data.beams.remove(lampKey);
        else data.beams.put(lampKey, wanted.keySet().toLongArray());
    }

    private static void clear(ServerLevel level, LevelData data, long lampKey) {
        long[] beams = data.beams.remove(lampKey);
        if (beams == null) return;
        for (long b : beams) if (unclaim(data, b)) remove(level, b);
    }

    /** Drops one claim; true if nobody claims the position any more. */
    private static boolean unclaim(LevelData data, long pos) {
        int left = data.claims.addTo(pos, -1) - 1;
        if (left <= 0) {
            data.claims.remove(pos);
            return true;
        }
        return false;
    }

    private static void place(ServerLevel level, BlockPos pos, int lightLevel) {
        if (!level.isLoaded(pos)) return;
        BlockState current = level.getBlockState(pos);
        Block block = beamLight.get();
        if (current.is(block)) {
            if (current.getValue(BeamLightBlock.LEVEL) >= lightLevel) return;
        } else if (!current.isAir()) {
            return; // never replace other blocks
        }
        level.setBlock(pos, block.defaultBlockState().setValue(BeamLightBlock.LEVEL, lightLevel), FLAGS);
    }

    private static void remove(ServerLevel level, long key) {
        BlockPos pos = BlockPos.of(key);
        if (!level.isLoaded(pos)) return;
        if (level.getBlockState(pos).is(beamLight.get())) level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
    }
}
