package colorfullights.compat;

import colorfullights.light.DynamicLights;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import java.util.List;

/**
 * Sable support (the sub-level library behind Create Aeronautics): light-emitting blocks on flying ships and
 * other sub-levels light up the world around them in color, as dynamic lights that follow the ship's pose, and
 * ships are tinted block by block by the light around them (see {@code SableShipTint}).
 * <p>
 * This class only touches Sable through {@link SableSubLevels}, which is loaded only when Sable is present.
 */
public final class SableCompat {
    public static final boolean LOADED = ModList.get().isLoaded("sable");

    private SableCompat() {
    }

    public static void collect(Level level, Vec3 camera, double range, DynamicLights.Collector collector) {
        SableSubLevels.collect(level, camera, range, collector);
    }

    /** Hooks ship tinting into the terrain shaders Sable draws ships with (via Veil). */
    public static void registerShaderHooks() {
        colorfullights.render.SableShipTint.register();
    }

    /** Shader programs are recreated on resource reload. */
    public static void onResourceReload() {
        colorfullights.render.SableShipTint.onReload();
    }

    /** World bounding boxes of sub-levels within range of the camera. */
    public static void collectBounds(Level level, Vec3 camera, double range, List<AABB> out) {
        SableSubLevels.collectBounds(level, camera, range, out);
    }

    /**
     * Whether a chunk section belongs to a sub-level's plot. Plot blocks are tinted by the world light around the
     * ship when it's drawn, not when its mesh is built. Safe from chunk meshing threads.
     */
    public static boolean isPlotSection(SectionPos pos) {
        return SableSubLevels.isPlotSection(pos);
    }

    /** World light for a plot section's mesh, or null if the section isn't on a sub-level. Meshing threads. */
    public static colorfullights.render.VanillaTerrainTint.PlotLight plotLight(SectionPos pos) {
        return SableShipBake.plotLight(pos);
    }

    /** Rebuilds ship meshes whose baked world light is out of date (shader pack mode). Client thread. */
    public static void tickBakedShips(boolean shaderPack) {
        SableShipBake.tick(shaderPack);
    }

    /** Rebuilds all ship meshes, e.g. when a shader pack is switched on or off. Client thread. */
    public static void rebuildShips() {
        SableShipBake.rebuildAll();
    }

    /** A block changed somewhere; if it's on a sub-level, that sub-level's lights are rescanned. */
    public static void onBlockChanged(BlockPos pos) {
        SableSubLevels.onBlockChanged(pos);
    }
}
