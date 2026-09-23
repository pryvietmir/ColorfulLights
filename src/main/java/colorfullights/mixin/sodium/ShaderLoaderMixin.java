package colorfullights.mixin.sodium;

import colorfullights.render.DynamicLightShader;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds per-pixel dynamic lighting to Sodium's terrain shaders. */
@Mixin(value = ShaderLoader.class, remap = false)
public abstract class ShaderLoaderMixin {
    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true)
    private static void colorful_lights$patch(ResourceLocation name, CallbackInfoReturnable<String> cir) {
        String source = cir.getReturnValue();
        String patched = DynamicLightShader.patch(name.toString(), source);
        if (patched != source) cir.setReturnValue(patched);
    }
}
