package colorfullights.mixin;

import colorfullights.render.WorldTint;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Particles (smoke, dust, rain splashes, ...) are tinted by the light color where they are. */
@Mixin(SingleQuadParticle.class)
public abstract class SingleQuadParticleMixin extends Particle {
    private SingleQuadParticleMixin(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z);
    }

    @ModifyVariable(
            method = "renderRotatedQuad(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;Lorg/joml/Quaternionf;F)V",
            at = @At("HEAD"),
            argsOnly = true
    )
    private VertexConsumer colorful_lights$tint(VertexConsumer consumer) {
        return WorldTint.wrapParticle(consumer, this.x, this.y, this.z);
    }
}
