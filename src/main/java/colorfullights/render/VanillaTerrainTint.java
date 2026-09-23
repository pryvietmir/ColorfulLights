package colorfullights.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import colorfullights.compat.SableCompat;
import colorfullights.light.ColorSampler;
import net.minecraft.core.SectionPos;

/**
 * Tints block quads emitted by the vanilla section compiler.
 * <p>
 * Vertices there are section-relative, so we remember the origin of the section being compiled on this thread.
 * Outside section compilation (items, falling blocks, ...) consumers are passed through untouched.
 */
public final class VanillaTerrainTint {
    private static final ThreadLocal<TintingConsumer> CURRENT = new ThreadLocal<>();

    private VanillaTerrainTint() {
    }

    public static void beginSection(SectionPos pos) {
        // Sable ship plots are tinted when drawn, by the world light where the ship currently is
        if (SableCompat.LOADED && SableCompat.isPlotSection(pos)) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(new TintingConsumer(pos.minBlockX(), pos.minBlockY(), pos.minBlockZ()));
    }

    public static void endSection() {
        CURRENT.remove();
    }

    public static VertexConsumer wrap(VertexConsumer consumer) {
        TintingConsumer tinting = CURRENT.get();
        if (tinting == null) return consumer;
        tinting.delegate = consumer;
        return tinting;
    }

    /** Forwards everything; rewrites color and light in the bulk vertex path that {@code putBulkData} uses. */
    private static final class TintingConsumer implements VertexConsumer {
        private final int ox, oy, oz;
        VertexConsumer delegate;

        TintingConsumer(int ox, int oy, int oz) {
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            long shaded = ColorSampler.shadeARGB(ox + x, oy + y, oz + z, color, light);
            delegate.addVertex(x, y, z, ColorSampler.color(shaded), u, v, overlay, ColorSampler.light(shaded), nx, ny, nz);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            delegate.setColor(r, g, b, a);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
    }
}
