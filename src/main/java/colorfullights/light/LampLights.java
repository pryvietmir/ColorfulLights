package colorfullights.light;

import colorfullights.DirectionalLamps;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Spot lights of {@link DirectionalLamps}: placed in the world, and helpers for lamps on moving things. Client. */
public final class LampLights {
    /** Placed lamps further than this from the camera don't get a beam. */
    private static final double RANGE = 64;
    /** At most this many placed lamps get a beam, the nearest first. */
    private static final int MAX_PLACED = 12;

    private LampLights() {
    }

    private record Found(BlockPos pos, BlockState state, DirectionalLamps.Lamp lamp, double dist2) {
    }

    /** Level provider: beams of placed lamps near the camera. */
    public static void collect(ClientLevel level, Vec3 camera, double range, DynamicLights.Collector collector) {
        if (DirectionalLamps.isEmpty()) return;
        List<Found> found = new ArrayList<>();
        int r = (int) Math.ceil(RANGE / 16);
        int cx = SectionPos.blockToSectionCoord(camera.x), cy = SectionPos.blockToSectionCoord(camera.y), cz = SectionPos.blockToSectionCoord(camera.z);
        for (int sx = cx - r; sx <= cx + r; sx++) {
            for (int sy = cy - r; sy <= cy + r; sy++) {
                for (int sz = cz - r; sz <= cz + r; sz++) {
                    long[] emitters = EmitterTracker.get(SectionPos.asLong(sx, sy, sz));
                    if (emitters == null) continue;
                    for (long e : emitters) {
                        BlockState state = Block.stateById(EmitterTracker.stateId(e));
                        DirectionalLamps.Lamp lamp = DirectionalLamps.of(state);
                        if (lamp == null) continue;
                        int local = EmitterTracker.localPos(e);
                        BlockPos pos = new BlockPos(sx << 4 | (local >> 8 & 15), sy << 4 | (local >> 4 & 15), sz << 4 | (local & 15));
                        double d2 = camera.distanceToSqr(Vec3.atCenterOf(pos));
                        if (d2 <= RANGE * RANGE) found.add(new Found(pos, state, lamp, d2));
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(Found::dist2));
        for (int i = 0; i < found.size() && i < MAX_PLACED; i++) {
            Found f = found.get(i);
            Direction d = DirectionalLamps.direction(f.lamp(), f.state());
            Vec3 dir = Vec3.atLowerCornerOf(d.getNormal());
            Vec3 origin = Vec3.atCenterOf(f.pos()).add(dir.scale(0.3));
            // the key includes the state, so turning the lamp makes a new source
            long key = Long.MIN_VALUE | 1L << 62
                    | ((f.pos().asLong() ^ Block.getId(f.state()) * 0x9E3779B97F4A7C15L) & 0x3FFFFFFFFFFFFFFFL);
            add(collector, key, pt -> origin, pt -> dir, f.state(), f.lamp());
        }
    }

    /**
     * The beam of a lamp on something moving (a contraption, a ship): {@code toWorld} turns a position in the
     * lamp's local frame into a world position at a partial tick. Returns false if the state isn't a lamp.
     */
    public static boolean addMoving(DynamicLights.Collector collector, long key, Vec3 localCenter, BlockState state,
                                    java.util.function.BiFunction<Vec3, Float, Vec3> toWorld) {
        DirectionalLamps.Lamp lamp = DirectionalLamps.of(state);
        if (lamp == null) return false;
        Vec3 localDir = Vec3.atLowerCornerOf(DirectionalLamps.direction(lamp, state).getNormal());
        Vec3 localOrigin = localCenter.add(localDir.scale(0.3));
        Vec3 localAhead = localOrigin.add(localDir);
        add(collector, key, pt -> toWorld.apply(localOrigin, pt), pt -> {
            Vec3 a = toWorld.apply(localOrigin, pt);
            Vec3 d = toWorld.apply(localAhead, pt).subtract(a);
            double len = d.length();
            return len > 1e-6 ? d.scale(1 / len) : new Vec3(0, 0, 1);
        }, state, lamp);
        return true;
    }

    private static void add(DynamicLights.Collector collector, long key, DynamicLights.Positioner origin,
                            DynamicLights.Positioner direction, BlockState state, DirectionalLamps.Lamp lamp) {
        collector.addSpot(key, () -> origin, () -> direction, LightColors.colorOf(Block.getId(state)), lamp.level(),
                lamp.inner(), lamp.outer(), lamp.range());
    }
}
