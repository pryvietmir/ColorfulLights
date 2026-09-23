package colorfullights.compat;

import colorfullights.light.DynamicLights;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import java.util.List;

/**
 * Create support: light-emitting blocks on moving contraptions (trains, elevators, bearings, ...)
 * light up the world around them in color, as dynamic lights. The contraptions themselves are tinted block by
 * block by the light around them (see {@code ObjectTintVolume}).
 * <p>
 * This class only touches Create through {@link CreateContraptions}, which is loaded only when Create is present.
 */
public final class CreateCompat {
    public static final boolean LOADED = ModList.get().isLoaded("create");

    private CreateCompat() {
    }

    public static void collect(Entity entity, DynamicLights.Collector collector) {
        CreateContraptions.collect(entity, collector);
    }

    /** Bounding boxes of contraptions within range of the camera, nearest first. */
    public static void collectContraptionBounds(Level level, Vec3 camera, double range, List<AABB> out) {
        CreateContraptions.collectBounds(level, camera, range, out);
    }

    public static boolean isContraption(Entity entity) {
        return CreateContraptions.isContraption(entity);
    }

    /** World position of a point given in a contraption's local space. */
    public static Vec3 toGlobal(Entity contraption, Vec3 local, float partialTick) {
        return CreateContraptions.toGlobal(contraption, local, partialTick);
    }
}
