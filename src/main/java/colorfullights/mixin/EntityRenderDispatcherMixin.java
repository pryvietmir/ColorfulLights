package colorfullights.mixin;

import colorfullights.compat.CreateCompat;
import colorfullights.render.WorldTint;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Mobs, players, dropped items and other entities are tinted by the light color where they stand. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true)
    private MultiBufferSource colorful_lights$tint(MultiBufferSource buffers, @Local(argsOnly = true) Entity entity) {
        // contraptions are tinted block by block elsewhere
        if (CreateCompat.LOADED && CreateCompat.isContraption(entity)) return buffers;
        return WorldTint.wrap(buffers, entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ());
    }
}
