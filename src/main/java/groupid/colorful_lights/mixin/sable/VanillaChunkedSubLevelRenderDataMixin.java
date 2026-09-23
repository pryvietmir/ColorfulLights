package groupid.colorful_lights.mixin.sable;

import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import groupid.colorful_lights.render.SableShipTint;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Switches the per-block light color on while Sable draws a ship. */
@Mixin(value = VanillaChunkedSubLevelRenderData.class, remap = false)
public abstract class VanillaChunkedSubLevelRenderDataMixin {
    @Inject(method = "renderChunkedSubLevel", at = @At("HEAD"))
    private void colorful_lights$begin(RenderType type, ShaderInstance shader, Matrix4f view, double camX, double camY, double camZ, CallbackInfo ci) {
        SableShipTint.begin(shader, view);
    }

    @Inject(method = "renderChunkedSubLevel", at = @At("RETURN"))
    private void colorful_lights$end(RenderType type, ShaderInstance shader, Matrix4f view, double camX, double camY, double camZ, CallbackInfo ci) {
        SableShipTint.end(shader);
    }
}
