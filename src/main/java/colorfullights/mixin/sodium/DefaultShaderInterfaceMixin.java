package colorfullights.mixin.sodium;

import colorfullights.render.SodiumDynamicLightUniforms;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.DefaultShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds the dynamic light uniforms to Sodium's terrain shader programs. */
@Mixin(value = DefaultShaderInterface.class, remap = false)
public abstract class DefaultShaderInterfaceMixin {
    @Unique
    private SodiumDynamicLightUniforms colorful_lights$uniforms;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void colorful_lights$bind(ShaderBindingContext context, ChunkShaderOptions options, CallbackInfo ci) {
        this.colorful_lights$uniforms = SodiumDynamicLightUniforms.bind(context);
    }

    @Inject(method = "setupState", at = @At("TAIL"))
    private void colorful_lights$setup(CallbackInfo ci) {
        if (this.colorful_lights$uniforms != null) this.colorful_lights$uniforms.setup();
    }
}
