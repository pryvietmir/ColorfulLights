package colorfullights;

import colorfullights.compat.IrisCompat;
import colorfullights.compat.SableCompat;
import colorfullights.debug.Timings;
import colorfullights.light.DynamicLights;
import colorfullights.light.LightColors;
import colorfullights.render.DynamicLightShader;
import colorfullights.render.ObjectTintVolume;
import colorfullights.render.WorldTint;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

final class ClientEvents {
    private ClientEvents() {
    }

    /** Resource packs can change block textures, so light colors are recomputed after every reload. */
    static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> {
            LightColors.invalidate();
            if (SableCompat.LOADED) SableCompat.onResourceReload();
        });
    }

    /**
     * (Re)builds the light color table on the client thread once no resource reload is in progress,
     * then re-meshes loaded chunks so they pick up the new colors. Also updates dynamic lights.
     */
    /** Whether dynamic lights were drawn by the Sodium shader last tick (vs. baked into meshes). */
    private static boolean wasInShader;

    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        IrisCompat.update();
        boolean inShader = DynamicLightShader.updateActive();
        if (inShader != wasInShader) {
            // e.g. an Iris shader pack was toggled: chunk meshes must gain or lose baked dynamic light
            wasInShader = inShader;
            DynamicLights.reset();
            if (mc.level != null) mc.levelRenderer.allChanged();
            if (SableCompat.LOADED) SableCompat.rebuildShips();
        }
        if (!LightColors.isReady() && mc.getOverlay() == null) {
            LightColors.rebuild();
            if (mc.level != null) mc.levelRenderer.allChanged();
        }
        DynamicLights.tick(mc);
        ObjectTintVolume.tick(mc);
        if (SableCompat.LOADED) SableCompat.tickBakedShips(IrisCompat.shaderPackActive());
        WorldTint.clear();
        Timings.tick();
    }

    static void onFrameStart(RenderFrameEvent.Pre event) {
        DynamicLightShader.onFrameStart();
    }
}
