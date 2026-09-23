package groupid.colorful_lights.mixin;

import com.mojang.blaze3d.vertex.VertexSorting;
import groupid.colorful_lights.render.VanillaTerrainTint;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Marks the current thread as compiling a section, so block quads can be mapped back to world space. */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
    private static final String COMPILE = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;";

    @Inject(method = COMPILE, at = @At("HEAD"))
    private void colorful_lights$begin(SectionPos sectionPos, RenderChunkRegion region, VertexSorting sorting, SectionBufferBuilderPack buffers,
                                       List<AddSectionGeometryEvent.AdditionalSectionRenderer> additional, CallbackInfoReturnable<SectionCompiler.Results> cir) {
        VanillaTerrainTint.beginSection(sectionPos);
    }

    @Inject(method = COMPILE, at = @At("RETURN"))
    private void colorful_lights$end(SectionPos sectionPos, RenderChunkRegion region, VertexSorting sorting, SectionBufferBuilderPack buffers,
                                     List<AddSectionGeometryEvent.AdditionalSectionRenderer> additional, CallbackInfoReturnable<SectionCompiler.Results> cir) {
        VanillaTerrainTint.endSection();
    }
}
