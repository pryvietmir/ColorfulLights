package groupid.colorful_lights.mixin.create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.render.ContraptionVisual;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import groupid.colorful_lights.light.ColorSampler;
import groupid.colorful_lights.render.FlywheelTint;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fallback for when Flywheel's shaders couldn't be patched ({@link FlywheelTint}): tints a Create contraption's
 * blocks (drawn by Flywheel as one instance) with one color, the light color at its center, updated every frame.
 */
@Mixin(value = ContraptionVisual.class, remap = false)
public abstract class ContraptionVisualMixin<E extends AbstractContraptionEntity> extends AbstractEntityVisual<E> {
    @Shadow
    protected TransformedInstance structure;

    @Unique
    private int colorful_lights$tint = 0xFFFFFF;
    /** The instance the tint was applied to; Create replaces it when the contraption's blocks change. */
    @Unique
    private TransformedInstance colorful_lights$tinted;

    private ContraptionVisualMixin(VisualizationContext ctx, E entity, float partialTick) {
        super(ctx, entity, partialTick);
    }

    @Inject(method = "beginFrame", at = @At("TAIL"))
    private void colorful_lights$tint(DynamicVisual.Context context, CallbackInfo ci) {
        if (structure == null || FlywheelTint.isActive()) return;
        Vec3 center = entity.getBoundingBox().getCenter();
        int tint = ColorSampler.objectTint(entity.level(), center.x, center.y, center.z);
        if (tint == colorful_lights$tint && structure == colorful_lights$tinted) return;
        colorful_lights$tint = tint;
        colorful_lights$tinted = structure;
        structure.colorRgb(tint);
        structure.setChanged();
    }
}
