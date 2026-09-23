package colorfullights.mixin;

import colorfullights.light.DynamicLights;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Entities standing near a dynamic light (or holding one) are lit by it. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    @Inject(method = "getBlockLightLevel", at = @At("RETURN"), cancellable = true)
    private void colorful_lights$dynamicLight(Entity entity, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        int dynamic = DynamicLights.levelAt(pos);
        if (dynamic > cir.getReturnValueI()) cir.setReturnValue(dynamic);
    }
}
