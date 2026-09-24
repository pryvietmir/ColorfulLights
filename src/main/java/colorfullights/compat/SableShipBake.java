package colorfullights.compat;

import colorfullights.light.ColorSampler;
import colorfullights.light.DynamicLights;
import colorfullights.light.EmitterTracker;
import colorfullights.render.VanillaTerrainTint;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.render.SubLevelRenderData;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * World light baked into Sable ship meshes, for when an Iris shader pack is active (the pack's shaders replace the
 * vanilla ones that otherwise light ships per frame, see {@code SableShipTint}).
 * <p>
 * While a ship's mesh is built, each vertex takes the light color and block light of the world where the ship is
 * at that moment. Meshes are rebuilt as the ship moves or turns, or as lights around it change, so moving ships
 * update in steps. Only loaded when Sable is installed.
 */
final class SableShipBake {
    /** Rebuild once the ship moved this far (blocks) or turned this much (radians) since its last bake. */
    private static final double MOVE_THRESHOLD = 2.0;
    private static final double TURN_THRESHOLD = Math.toRadians(10);
    /** At most one rebuild per ship this often (ticks). */
    private static final int MIN_INTERVAL = 10;
    /** Light changes (placed or dynamic) are picked up at most this often (ticks). */
    private static final int LIGHT_INTERVAL = 20;

    private static final class Baked {
        final Vector3d position = new Vector3d();
        final Quaterniond orientation = new Quaterniond();
        long lightVersion;
        int lastRebuild = Integer.MIN_VALUE / 2;
        boolean valid;
    }

    private static final Map<ClientSubLevel, Baked> BAKED = new WeakHashMap<>();
    private static int ticks;

    private SableShipBake() {
    }

    // --- meshing threads ---

    static VanillaTerrainTint.PlotLight plotLight(SectionPos pos) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return null;
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return null;
        LevelPlot plot = container.getPlot(new ChunkPos(pos.x(), pos.z()));
        if (plot == null || !(plot.getSubLevel() instanceof ClientSubLevel subLevel)) return null;

        Matrix4d toWorld = subLevel.logicalPose().bakeIntoMatrix(new Matrix4d());
        Vector3d world = new Vector3d();
        Long2LongOpenHashMap cache = new Long2LongOpenHashMap(); // per section: many vertices share a block
        cache.defaultReturnValue(-1L);
        return (x, y, z) -> {
            toWorld.transformPosition(world.set(x, y, z));
            long key = BlockPos.asLong((int) Math.floor(world.x), (int) Math.floor(world.y), (int) Math.floor(world.z));
            long light = cache.get(key);
            if (light == -1L) {
                light = ColorSampler.objectLight(level, world.x, world.y, world.z);
                cache.put(key, light);
            }
            return light;
        };
    }

    // --- client thread ---

    static void tick(boolean shaderPack) {
        ticks++;
        ClientLevel level = Minecraft.getInstance().level;
        if (!shaderPack || level == null) {
            BAKED.values().forEach(b -> b.valid = false);
            return;
        }
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        long lightVersion = (long) EmitterTracker.version() << 32 | (DynamicLights.version() & 0xFFFFFFFFL);

        for (ClientSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) continue;
            Baked baked = BAKED.computeIfAbsent(subLevel, s -> new Baked());
            if (ticks - baked.lastRebuild < MIN_INTERVAL) continue;
            Pose3dc pose = subLevel.logicalPose();
            boolean stale = !baked.valid
                    || baked.position.distance(pose.position()) >= MOVE_THRESHOLD
                    || angle(baked.orientation, pose.orientation()) >= TURN_THRESHOLD
                    || (baked.lightVersion != lightVersion && ticks - baked.lastRebuild >= LIGHT_INTERVAL);
            if (!stale) continue;
            if (rebuild(subLevel)) {
                baked.position.set(pose.position());
                baked.orientation.set(pose.orientation());
                baked.lightVersion = lightVersion;
                baked.lastRebuild = ticks;
                baked.valid = true;
            }
        }
    }

    static void rebuildAll() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        for (ClientSubLevel subLevel : container.getAllSubLevels()) {
            if (!subLevel.isRemoved()) rebuild(subLevel);
        }
        BAKED.values().forEach(b -> b.valid = false);
    }

    private static boolean rebuild(ClientSubLevel subLevel) {
        SubLevelRenderData data = subLevel.getRenderData();
        if (data == null) return false;
        data.rebuild();
        return true;
    }

    /** Rotation angle between two orientations, in radians. */
    private static double angle(Quaterniond a, org.joml.Quaterniondc b) {
        double dot = Math.abs(a.x * b.x() + a.y * b.y() + a.z * b.z() + a.w * b.w());
        return 2 * Math.acos(Math.min(1.0, dot));
    }
}
