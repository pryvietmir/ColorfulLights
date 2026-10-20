package colorfullights.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;

/**
 * Rain at the camera from Simple Clouds, whose storms are local (it rains only under a cloud), through its API by
 * reflection, so there is no dependency on it. Without Simple Clouds, or when it runs vanilla weather, this is
 * vanilla's global rain level.
 */
public final class SimpleCloudsRain {
    private static boolean resolved;
    private static Object api;
    private static Method getCloudManager, getRainLevel, useVanilla;

    private SimpleCloudsRain() {}

    private static void resolve() {
        resolved = true;
        if (!ModList.get().isLoaded("simpleclouds")) return;
        try {
            Class<?> apiClass = Class.forName("dev.nonamecrackers2.simpleclouds.api.SimpleCloudsAPI");
            Class<?> managerClass = Class.forName("dev.nonamecrackers2.simpleclouds.api.common.world.ScAPICloudManager");
            api = apiClass.getMethod("getApi").invoke(null);
            getCloudManager = apiClass.getMethod("getCloudManager", Level.class);
            getRainLevel = managerClass.getMethod("getRainLevel", float.class, float.class, float.class);
            useVanilla = managerClass.getMethod("shouldUseVanillaWeather");
        } catch (Throwable e) {
            api = null;
        }
    }

    /** Rain 0..1 where the camera is. */
    public static float rainAtCamera(Minecraft mc, float partialTick) {
        Level level = mc.level;
        float vanilla = level.getRainLevel(partialTick);
        if (!resolved) resolve();
        if (api == null) return vanilla;
        try {
            Object manager = getCloudManager.invoke(api, level);
            if (manager == null || (Boolean) useVanilla.invoke(manager)) return vanilla;
            var cam = mc.gameRenderer.getMainCamera().getPosition();
            float rain = (Float) getRainLevel.invoke(manager, (float) cam.x, (float) cam.y, (float) cam.z);
            return Math.max(0f, Math.min(1f, rain));
        } catch (Throwable e) {
            return vanilla;
        }
    }
}
