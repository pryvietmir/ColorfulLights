package colorfullights.mixin;

import colorfullights.render.WorldTint;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Chests, signs, banners and other block entities are tinted by the light color at their block. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true)
    private MultiBufferSource colorful_lights$tint(MultiBufferSource buffers, @Local(argsOnly = true) BlockEntity blockEntity) {
        // light sources show their own texture, as in terrain
        if (blockEntity.getBlockState().getLightEmission() > 0) return buffers;
        BlockPos pos = blockEntity.getBlockPos();
        return WorldTint.wrap(buffers, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }
}
