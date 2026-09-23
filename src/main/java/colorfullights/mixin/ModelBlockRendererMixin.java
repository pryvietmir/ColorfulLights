package colorfullights.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import colorfullights.render.VanillaTerrainTint;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Vanilla chunk meshing path (used when Sodium is not installed). */
@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererMixin {
    @WrapOperation(
            method = "putQuadData",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;putBulkData(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/renderer/block/model/BakedQuad;[FFFFF[IIZ)V")
    )
    private void colorful_lights$tint(VertexConsumer consumer, PoseStack.Pose pose, BakedQuad quad, float[] brightness,
                                      float r, float g, float b, float a, int[] lightmap, int overlay, boolean readExistingColor,
                                      Operation<Void> original, @Local(argsOnly = true) BlockState state) {
        // Light sources show their own texture; only surfaces they illuminate get tinted.
        VertexConsumer target = state.getLightEmission() > 0 ? consumer : VanillaTerrainTint.wrap(consumer);
        original.call(target, pose, quad, brightness, r, g, b, a, lightmap, overlay, readExistingColor);
    }
}
