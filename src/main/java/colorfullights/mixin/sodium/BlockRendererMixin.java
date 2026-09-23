package colorfullights.mixin.sodium;

import colorfullights.light.ColorSampler;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sodium block meshing: tint each quad's vertex colors right before they are written to the mesh buffer. */
@Mixin(value = BlockRenderer.class, remap = false)
public abstract class BlockRendererMixin {
    @Shadow
    @Final
    private ChunkVertexEncoder.Vertex[] vertices;

    /** World position minus section-relative position of the block being rendered. */
    @Unique
    private int colorful_lights$dx, colorful_lights$dy, colorful_lights$dz;

    /** Light sources show their own texture; only surfaces they illuminate get tinted. */
    @Unique
    private boolean colorful_lights$skip;

    @Inject(method = "renderModel", at = @At("HEAD"))
    private void colorful_lights$capturePos(BakedModel model, BlockState state, BlockPos pos, BlockPos origin, CallbackInfo ci) {
        this.colorful_lights$dx = pos.getX() - origin.getX();
        this.colorful_lights$dy = pos.getY() - origin.getY();
        this.colorful_lights$dz = pos.getZ() - origin.getZ();
        this.colorful_lights$skip = state.getLightEmission() > 0;
    }

    @Inject(
            method = "bufferQuad",
            at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/builder/ChunkMeshBufferBuilder;push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V")
    )
    private void colorful_lights$tint(CallbackInfo ci) {
        if (this.colorful_lights$skip) return;
        for (ChunkVertexEncoder.Vertex v : this.vertices) {
            long shaded = ColorSampler.shadeABGR(v.x + colorful_lights$dx, v.y + colorful_lights$dy, v.z + colorful_lights$dz, v.color, v.light);
            v.color = ColorSampler.color(shaded);
            v.light = ColorSampler.light(shaded);
        }
    }
}
