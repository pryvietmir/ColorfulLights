package colorfullights.mixin.flywheel;

import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import colorfullights.render.FlywheelTint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds the light color grid to Flywheel programs. */
@Mixin(value = GlProgram.class, remap = false)
public abstract class GlProgramMixin {
    @Inject(method = "bind", at = @At("TAIL"))
    private void colorful_lights$bind(CallbackInfo ci) {
        FlywheelTint.onProgramBound(((GlProgram) (Object) this).handle());
    }

    @Inject(method = "deleteInternal", at = @At("HEAD"))
    private void colorful_lights$delete(int handle, CallbackInfo ci) {
        FlywheelTint.onProgramDeleted(handle);
    }
}
