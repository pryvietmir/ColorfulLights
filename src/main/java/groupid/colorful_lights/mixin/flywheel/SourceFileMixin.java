package groupid.colorful_lights.mixin.flywheel;

import com.llamalad7.mixinextras.sugar.Local;
import dev.engine_room.flywheel.backend.glsl.SourceFile;
import groupid.colorful_lights.render.FlywheelTint;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Adds per-pixel light color tinting to Flywheel's shared fragment code. */
@Mixin(value = SourceFile.class, remap = false)
public abstract class SourceFileMixin {
    @ModifyVariable(method = "parse", at = @At("HEAD"), argsOnly = true)
    private static String colorful_lights$patch(String source, @Local(argsOnly = true) ResourceLocation name) {
        return FlywheelTint.patch(name.toString(), source);
    }
}
