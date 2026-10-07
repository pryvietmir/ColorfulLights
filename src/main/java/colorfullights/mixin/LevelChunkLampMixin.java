package colorfullights.mixin;

import colorfullights.beam.BeamLights;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Server side: directional lamps placed, broken or turned update their beam lights. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkLampMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void colorful_lights$trackLamp(BlockPos pos, BlockState state, boolean isMoving, CallbackInfoReturnable<BlockState> cir) {
        BlockState old = cir.getReturnValue();
        if (old != null && ((LevelChunk) (Object) this).getLevel() instanceof ServerLevel level) {
            BeamLights.onBlockChanged(level, pos, old, state);
        }
    }
}
