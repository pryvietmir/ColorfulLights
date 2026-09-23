package colorfullights.mixin;

import colorfullights.render.WorldTint;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** First-person hands and held items are tinted by the light color at the player's eyes. */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @ModifyVariable(method = "renderArmWithItem", at = @At("HEAD"), argsOnly = true)
    private MultiBufferSource colorful_lights$tint(MultiBufferSource buffers, @Local(argsOnly = true) AbstractClientPlayer player) {
        Vec3 eyes = player.getEyePosition();
        return WorldTint.wrap(buffers, eyes.x, eyes.y, eyes.z);
    }
}
