package colorfullights.light;

import colorfullights.Config;
import colorfullights.DirectionalLamps;
import colorfullights.lamp.LampSettings;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Spot lights of {@link DirectionalLamps}: placed in the world, and helpers for lamps on moving things. Client. */
public final class LampLights {
    /** Placed lamps further than this from the camera don't get a beam. */
    private static final double RANGE = 64;
    /** At most this many placed lamps get a beam, those whose beams show most in the frame first. */
    private static final int MAX_PLACED = 12;

    private LampLights() {
    }

    private record Found(BlockPos pos, BlockState state, DirectionalLamps.Lamp lamp) {
    }

    /** Lit lamps around the camera, found by the last scan (client thread). */
    private static List<Found> lamps = List.of();
    private static int scannedVersion = Integer.MIN_VALUE;
    private static long scannedSection = Long.MIN_VALUE;
    private static int scanAge;
    /** Rescan at least this often (ticks), even if no emitter changed and the camera stayed in its section. */
    private static final int RESCAN_TICKS = 40;
    /** Lamps that had a beam last tick (by position); they keep it unless others show clearly more. */
    private static LongOpenHashSet chosen = new LongOpenHashSet();
    private static final float KEEP_CHOSEN = 1.5f;

    /** Level provider: beams of placed lamps near the camera. */
    public static void collect(ClientLevel level, Vec3 camera, double range, DynamicLights.Collector collector) {
        long start = colorfullights.debug.Timings.start();
        try {
            collectImpl(level, camera, collector);
        } finally {
            colorfullights.debug.Timings.end(colorfullights.debug.Timings.Part.LAMP_COLLECT, start);
        }
    }

    /** The lit lamps within reach of the camera, from the emitter index (only rescanned when something changed). */
    private static List<Found> lampsAround(Vec3 camera) {
        long section = SectionPos.asLong(SectionPos.blockToSectionCoord(camera.x), SectionPos.blockToSectionCoord(camera.y),
                SectionPos.blockToSectionCoord(camera.z));
        int version = EmitterTracker.version();
        if (version == scannedVersion && section == scannedSection && ++scanAge < RESCAN_TICKS) return lamps;
        scannedVersion = version;
        scannedSection = section;
        scanAge = 0;
        List<Found> found = new ArrayList<>();
        int r = (int) Math.ceil(RANGE / 16);
        int cx = SectionPos.x(section), cy = SectionPos.y(section), cz = SectionPos.z(section);
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
                        found.add(new Found(new BlockPos(sx << 4 | (local >> 8 & 15), sy << 4 | (local >> 4 & 15), sz << 4 | (local & 15)),
                                state, lamp));
                    }
                }
            }
        }
        return lamps = found;
    }

    /**
     * How much a lamp's beam shows in the frame: roughly the solid angle of the sphere around its cone, a small
     * share if that sphere is out of view. By the beam, not the lamp: a lamp behind the camera lighting the view
     * keeps its beam.
     */
    private static float score(Found f, Vec3 camera, Frustum frustum) {
        long settings = LampSettings.client(f.pos().asLong());
        float length = f.lamp().range() * Config.spotRange * LampSettings.range(settings);
        float outer = Math.min(75f, f.lamp().outer() / (Config.spotFocus * LampSettings.focus(settings)));
        double tan = Math.min(4.0, Math.tan(Math.toRadians(outer)));
        Direction d = DirectionalLamps.direction(f.lamp(), f.state());
        if (camera.distanceToSqr(Vec3.atCenterOf(f.pos())) > (RANGE + length) * (RANGE + length)) return 0f;
        float score = DynamicLights.spotScore(camera, f.pos().getX() + 0.5, f.pos().getY() + 0.5, f.pos().getZ() + 0.5,
                d.getStepX(), d.getStepY(), d.getStepZ(), length, (float) tan, f.lamp().level(), frustum);
        if (chosen.contains(f.pos().asLong())) score *= KEEP_CHOSEN;
        return score;
    }

    private static void collectImpl(ClientLevel level, Vec3 camera, DynamicLights.Collector collector) {
        if (!Config.spotLights || Config.maxBeams <= 0 || DirectionalLamps.isEmpty()) return;
        List<Found> all = lampsAround(camera);
        if (all.isEmpty()) {
            chosen.clear();
            return;
        }
        Frustum frustum = Minecraft.getInstance().levelRenderer.getFrustum();
        int n = all.size();
        float[] scores = new float[n];
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            scores[i] = score(all.get(i), camera, frustum);
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Float.compare(scores[b], scores[a]));
        LongOpenHashSet nowChosen = new LongOpenHashSet();
        for (int k = 0; k < n && k < Math.min(MAX_PLACED, Config.maxBeams); k++) {
            if (scores[order[k]] <= 0f) break;
            Found f = all.get(order[k]);
            nowChosen.add(f.pos().asLong());
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
        chosen = nowChosen;
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
