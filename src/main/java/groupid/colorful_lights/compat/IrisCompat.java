package groupid.colorful_lights.compat;

import groupid.colorful_lights.Colorful_lights;
import net.neoforged.fml.ModList;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Iris support. While a shader pack is in use, Iris draws terrain with the pack's programs instead of Sodium's,
 * so the per-pixel dynamic lights in Sodium's shader are unavailable and dynamic light is baked into chunk meshes
 * (vertex light and color), which shader packs pick up like any other block light.
 * <p>
 * Uses Iris' stable public API ({@code net.irisshaders.iris.api.v0.IrisApi}) reflectively, so no compile dependency.
 */
public final class IrisCompat {
    public static final boolean LOADED = ModList.get().isLoaded("iris");

    private static final MethodHandle SHADER_PACK_IN_USE = lookup();

    private IrisCompat() {
    }

    private static MethodHandle lookup() {
        if (!LOADED) return null;
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            return MethodHandles.publicLookup()
                    .findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class))
                    .bindTo(instance);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Colorful_lights.LOGGER.warn("Iris is installed but its API could not be accessed; assuming no shader pack", e);
            return null;
        }
    }

    /** True while an Iris shader pack is active. */
    public static boolean isShaderPackInUse() {
        if (SHADER_PACK_IN_USE == null) return false;
        try {
            return (boolean) SHADER_PACK_IN_USE.invokeExact();
        } catch (Throwable t) {
            return false;
        }
    }
}
