package colorfullights.compat;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import colorfullights.DirectionalLamps;
import colorfullights.light.DynamicLights;
import colorfullights.light.LampLights;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Direct Create API access; only loaded when Create is installed (see {@link CreateCompat}). */
final class CreateContraptions {
    /** A contraption can carry lots of lamps; beyond this they add rebuild cost without visible benefit. */
    private static final int MAX_PER_CONTRAPTION = 32;

    private record Emitter(Vec3 local, BlockState state, List<DirectionalLamps.Head> heads) {
    }

    private record Cached(int blockCount, List<Emitter> emitters) {
    }

    /** Emitting blocks per contraption; contraption blocks rarely change, so scanning every tick is wasteful. */
    private static final Map<Contraption, Cached> CACHE = new WeakHashMap<>();

    private CreateContraptions() {
    }

    static void collect(Entity entity, DynamicLights.Collector collector) {
        if (!(entity instanceof AbstractContraptionEntity contraptionEntity)) return;
        Contraption contraption = contraptionEntity.getContraption();
        if (contraption == null) return;

        Map<BlockPos, StructureTemplate.StructureBlockInfo> blocks = contraption.getBlocks();
        Cached cached = CACHE.get(contraption);
        if (cached == null || cached.blockCount() != blocks.size()) {
            List<Emitter> emitters = new ArrayList<>();
            for (Map.Entry<BlockPos, StructureTemplate.StructureBlockInfo> entry : blocks.entrySet()) {
                BlockState state = entry.getValue().state();
                if (state.getLightEmission() <= 0) continue;
                // lamps that hold several heads keep them in their block entity data
                DirectionalLamps.Lamp lamp = DirectionalLamps.of(state);
                List<DirectionalLamps.Head> heads = lamp != null && lamp.heads() != null
                        ? lamp.heads().fromTag(state, entry.getValue().nbt()) : null;
                emitters.add(new Emitter(Vec3.atCenterOf(entry.getKey()), state, heads));
                if (emitters.size() >= MAX_PER_CONTRAPTION) break;
            }
            cached = new Cached(blocks.size(), emitters);
            CACHE.put(contraption, cached);
        }

        for (Emitter e : cached.emitters()) {
            // Key per contraption and lamp position (stable if the block list changes); bit 62 keeps it apart
            // from the entity's own lights (entity id << 8 | index).
            BlockPos p = BlockPos.containing(e.local());
            long key = 1L << 62 | ((long) entity.getId() & 0x3FFFFFFFFL) << 24
                    | (p.getX() & 0xFFL) << 16 | (p.getY() & 0xFFL) << 8 | (p.getZ() & 0xFFL);
            Vec3 local = e.local();
            // directional lamps (headlights) shine along the contraption's turn
            if (LampLights.addMoving(collector, key, local, e.state(), (v, pt) -> toGlobal(entity, v, pt), 0L, e.heads())) continue;
            collector.add(key, () -> pt -> toGlobal(entity, local, pt), e.state(), e.state().getLightEmission());
        }
    }

    static void collectBounds(Level level, Vec3 camera, double range, List<AABB> out) {
        AABB area = new AABB(camera, camera).inflate(range);
        List<AbstractContraptionEntity> found = level.getEntitiesOfClass(AbstractContraptionEntity.class, area);
        found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(camera)));
        for (AbstractContraptionEntity e : found) {
            if (e.getContraption() != null) out.add(e.getBoundingBox());
        }
    }

    static boolean isContraption(Entity entity) {
        return entity instanceof AbstractContraptionEntity;
    }

    static Vec3 toGlobal(Entity entity, Vec3 local, float partialTick) {
        return ((AbstractContraptionEntity) entity).toGlobalVector(local, partialTick);
    }
}
