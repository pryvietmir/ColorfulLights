package groupid.colorful_lights.mixin.sodium;

import groupid.colorful_lights.light.ColorSampler;
import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sodium fluid meshing (water, lava): same idea as {@link BlockRendererMixin}. */
@Mixin(value = DefaultFluidRenderer.class, remap = false)
public abstract class DefaultFluidRendererMixin {
    @Shadow
    @Final
    private ChunkVertexEncoder.Vertex[] vertices;

    @Unique
    private int colorful_lights$dx, colorful_lights$dy, colorful_lights$dz;

    /** Glowing fluids (lava) keep their own texture, like other light sources. */
    @Unique
    private boolean colorful_lights$skip;

    @Inject(method = "render", at = @At("HEAD"))
    private void colorful_lights$capturePos(LevelSlice level, BlockState blockState, FluidState fluidState, BlockPos blockPos, BlockPos offset,
                                            TranslucentGeometryCollector collector, ChunkModelBuilder meshBuilder, Material material,
                                            ColorProvider<FluidState> colorProvider, TextureAtlasSprite[] sprites, CallbackInfo ci) {
        this.colorful_lights$dx = blockPos.getX() - offset.getX();
        this.colorful_lights$dy = blockPos.getY() - offset.getY();
        this.colorful_lights$dz = blockPos.getZ() - offset.getZ();
        this.colorful_lights$skip = blockState.getLightEmission() > 0;
    }

    @Inject(
            method = "writeQuad",
            at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/builder/ChunkMeshBufferBuilder;push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/Material;)V")
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
