package colorfullights.mixin;

import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets directional lamps get a weaker all-around light than they declare (see DirectionalLamps). */
@Mixin(BlockBehaviour.BlockStateBase.class)
public interface BlockStateBaseAccessor {
    @Mutable
    @Accessor("lightEmission")
    void colorful_lights$setLightEmission(int lightEmission);
}
