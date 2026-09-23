package colorfullights.mixin.create;

import com.simibubi.create.content.contraptions.render.ContraptionEntityRenderer;
import colorfullights.render.ObjectTintVolume;
import colorfullights.render.TintingBufferSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Contraptions drawn without Flywheel (backend off, or an Iris shader pack active) are tinted block by block on
 * the CPU, from the same light color grid Flywheel samples on the GPU.
 */
@Mixin(value = ContraptionEntityRenderer.class, remap = false)
public abstract class ContraptionEntityRendererMixin {
    @ModifyVariable(
            method = "render(Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"),
            argsOnly = true
    )
    private MultiBufferSource colorful_lights$tint(MultiBufferSource buffers) {
        if (!ObjectTintVolume.hasBoxes()) return buffers;
        return new TintingBufferSource(buffers, Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
    }
}
