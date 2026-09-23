package groupid.colorful_lights.compat;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import groupid.colorful_lights.light.DynamicLights;
import net.minecraft.client.multiplayer.ClientLevel;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Direct Sable API access; only loaded when Sable is installed (see {@link SableCompat}). */
final class SableSubLevels {
    /** Beyond this, more lamps add cost without visible benefit. */
    private static final int MAX_PER_SUB_LEVEL = 32;
    /** Sub-level lamps are rescanned this often even without a known block change (plot chunks may stream in). */
    private static final int RESCAN_TICKS = 40;

    private record Emitter(Vec3 plotPos, BlockState state) {
    }

    private static final class Cached {
        List<Emitter> emitters = List.of();
        int age;
        boolean dirty = true;
    }

    private static final Map<ClientSubLevel, Cached> CACHE = new WeakHashMap<>();

    private SableSubLevels() {
    }

    static void collect(Level level, Vec3 camera, double range, DynamicLights.Collector collector) {
        if (!(level instanceof ClientLevel clientLevel)) return;
        ClientSubLevelContainer container = SubLevelContainer.getContainer(clientLevel);
        if (container == null) return;

        for (ClientSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) continue;
            Vec3 center = subLevel.logicalPose().transformPosition(Vec3.atCenterOf(subLevel.getPlot().getCenterBlock()));
            if (center.distanceToSqr(camera) > (range + 64) * (range + 64)) continue;

            Cached cached = CACHE.computeIfAbsent(subLevel, s -> new Cached());
            if (cached.dirty || ++cached.age > RESCAN_TICKS) {
                cached.emitters = scan(subLevel);
                cached.age = 0;
                cached.dirty = false;
            }

            // Key per sub-level and lamp position (stable across rescans); the sign bit keeps it apart from entities.
            long base = Long.MIN_VALUE | (subLevel.getUniqueId().getLeastSignificantBits() & 0x7FFFFFL) << 32;
            for (Emitter e : cached.emitters) {
                Vec3 p = e.plotPos();
                long key = base | ((long) Math.floor(p.x) & 0x7FF) << 21 | ((long) Math.floor(p.y) & 0x3FF) << 11
                        | ((long) Math.floor(p.z) & 0x7FF);
                collector.add(key, () -> pt -> subLevel.renderPose(pt).transformPosition(p),
                        e.state(), e.state().getLightEmission());
            }
        }
    }

    static void collectBounds(Level level, Vec3 camera, double range, List<AABB> out) {
        if (!(level instanceof ClientLevel clientLevel)) return;
        ClientSubLevelContainer container = SubLevelContainer.getContainer(clientLevel);
        if (container == null) return;
        for (ClientSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) continue;
            BoundingBox3dc b = subLevel.boundingBox();
            AABB box = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
            if (box.distanceToSqr(camera) <= range * range) out.add(box);
        }
    }

    static boolean isPlotSection(SectionPos pos) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return false;
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        return container != null && container.inBounds(pos.x(), pos.z());
    }

    /** Light-emitting blocks in a sub-level's plot, at their plot-space block centers. */
    private static List<Emitter> scan(ClientSubLevel subLevel) {
        List<Emitter> out = new ArrayList<>();
        for (PlotChunkHolder holder : subLevel.getPlot().getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk == null) continue;
            ChunkPos cp = chunk.getPos();
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                LevelChunkSection section = sections[i];
                if (section.hasOnlyAir() || !section.maybeHas(s -> s.getLightEmission() > 0)) continue;
                int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++)
                        for (int x = 0; x < 16; x++) {
                            BlockState state = section.getBlockState(x, y, z);
                            if (state.getLightEmission() <= 0) continue;
                            out.add(new Emitter(new Vec3(cp.getMinBlockX() + x + 0.5, baseY + y + 0.5, cp.getMinBlockZ() + z + 0.5), state));
                            if (out.size() >= MAX_PER_SUB_LEVEL) return out;
                        }
            }
        }
        return out;
    }

    static void onBlockChanged(BlockPos pos) {
        for (Map.Entry<ClientSubLevel, Cached> entry : CACHE.entrySet()) {
            if (entry.getKey().getPlot().contains(Vec3.atCenterOf(pos))) {
                entry.getValue().dirty = true; // rescan next tick
            }
        }
    }
}
