package colorfullights.light;

import colorfullights.Config;
import colorfullights.DirectionalLamps;
import colorfullights.lamp.LampSettings;
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
        if (!Config.spotLights || Config.maxBeams <= 0 || DirectionalLamps.isEmpty()) return;
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
                        if (lamp == null || !DirectionalLamps.isLit(state)) continue;
                        int local = EmitterTracker.localPos(e);
                        BlockPos pos = new BlockPos(sx << 4 | (local >> 8 & 15), sy << 4 | (local >> 4 & 15), sz << 4 | (local & 15));
                        double d2 = camera.distanceToSqr(Vec3.atCenterOf(pos));
                        if (d2 <= RANGE * RANGE) found.add(new Found(pos, state, lamp, d2));
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(Found::dist2));
        for (int i = 0; i < found.size() && i < Math.min(MAX_PLACED, Config.maxBeams); i++) {
            Found f = found.get(i);
            Direction d = DirectionalLamps.direction(f.lamp(), f.state());
            Vec3 dir = Vec3.atLowerCornerOf(d.getNormal());
            Vec3 center = Vec3.atCenterOf(f.pos());
            // the key includes the state, so turning the lamp makes a new source
            long key = Long.MIN_VALUE | 1L << 62
                    | ((f.pos().asLong() ^ Block.getId(f.state()) * 0x9E3779B97F4A7C15L) & 0x3FFFFFFFFFFFFFFFL);
            long settings = LampSettings.client(f.pos().asLong());
            if (f.lamp().heads() == null) {
                Vec3 origin = center.add(dir.scale(0.3));
                add(collector, key, pt -> origin, pt -> dir, f.state(), f.lamp(), settings, -1);
                continue;
            }
            List<DirectionalLamps.Head> heads = f.lamp().heads().fromBlockEntity(f.state(), level.getBlockEntity(f.pos()));
            for (int h = 0; h < heads.size(); h++) {
                Vec3 origin = center.add(heads.get(h).offset());
                add(collector, headKey(key, h), pt -> origin, pt -> dir, f.state(), f.lamp(), settings, heads.get(h).color());
            }
        }
    }

    /** Key of one of several lamps of a block (bits 58-61 are free in the keys of lamps on contraptions and ships). */
    private static long headKey(long key, int head) {
        return key ^ ((long) (head + 1) << 58);
    }

    /**
     * The beam of a lamp on something moving (a contraption, a ship): {@code toWorld} turns a position in the
     * lamp's local frame into a world position at a partial tick, {@code settings} are the lamp's {@link LampSettings},
     * {@code heads} the lamps of a block holding several (see {@link DirectionalLamps.Heads}; null: one lamp).
     * Returns false if the state isn't a lamp that shines.
     */
    public static boolean addMoving(DynamicLights.Collector collector, long key, Vec3 localCenter, BlockState state,
                                    java.util.function.BiFunction<Vec3, Float, Vec3> toWorld, long settings,
                                    List<DirectionalLamps.Head> heads) {
        DirectionalLamps.Lamp lamp = DirectionalLamps.of(state);
        if (lamp == null || !Config.spotLights || !DirectionalLamps.isLit(state)) return false;
        Vec3 localDir = Vec3.atLowerCornerOf(DirectionalLamps.direction(lamp, state).getNormal());
        if (lamp.heads() == null) {
            addMovingHead(collector, key, localCenter.add(localDir.scale(0.3)), localDir, state, lamp, toWorld, settings, -1);
        } else if (heads != null) {
            for (int h = 0; h < heads.size(); h++) {
                addMovingHead(collector, headKey(key, h), localCenter.add(heads.get(h).offset()), localDir, state, lamp,
                        toWorld, settings, heads.get(h).color());
            }
        }
        return true;
    }

    private static void addMovingHead(DynamicLights.Collector collector, long key, Vec3 localOrigin, Vec3 localDir,
                                      BlockState state, DirectionalLamps.Lamp lamp,
                                      java.util.function.BiFunction<Vec3, Float, Vec3> toWorld, long settings, int headColor) {
        Vec3 localAhead = localOrigin.add(localDir);
        add(collector, key, pt -> toWorld.apply(localOrigin, pt), pt -> {
            Vec3 a = toWorld.apply(localOrigin, pt);
            Vec3 d = toWorld.apply(localAhead, pt).subtract(a);
            double len = d.length();
            return len > 1e-6 ? d.scale(1 / len) : new Vec3(0, 0, 1);
        }, state, lamp, settings, headColor);
    }

    private static void add(DynamicLights.Collector collector, long key, DynamicLights.Positioner origin,
                            DynamicLights.Positioner direction, BlockState state, DirectionalLamps.Lamp lamp, long settings,
                            int headColor) {
        // painted with a dye (our settings, or the lamp's own dye), else the color for all lamps, else its own
        int color = LampSettings.color(settings);
        if (color < 0) color = headColor;
        if (color < 0) color = DirectionalLamps.colorOf(lamp);
        if (color < 0) color = LightColors.colorOf(Block.getId(state));
        float focus = Config.spotFocus * LampSettings.focus(settings);
        collector.addSpot(key, () -> origin, () -> direction, color, lamp.level(),
                lamp.inner() / focus, lamp.outer() / focus, lamp.range() * Config.spotRange * LampSettings.range(settings));
    }
}
