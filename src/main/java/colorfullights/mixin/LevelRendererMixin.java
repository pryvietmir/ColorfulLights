package colorfullights.mixin;

import colorfullights.light.DynamicLights;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Block entities, particles and anything else lit through {@code getLightColor} see dynamic lights too. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(
            method = "getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
            at = @At("RETURN"),
            cancellable = true
    )
    private static void colorful_lights$dynamicLight(BlockAndTintGetter level, BlockState state, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        // Only the real client world: chunk meshing passes region snapshots (dynamic light is added to terrain
        // separately), and other worlds (Create contraptions, Ponder scenes, previews) have no dynamic lights.
        if (level != Minecraft.getInstance().level) return;
        int dynamic = DynamicLights.levelAt(pos);
        if (dynamic <= 0) return;
        int packed = cir.getReturnValueI();
        if (dynamic > LightTexture.block(packed)) {
            cir.setReturnValue(LightTexture.pack(dynamic, LightTexture.sky(packed)));
        }
    }
}
