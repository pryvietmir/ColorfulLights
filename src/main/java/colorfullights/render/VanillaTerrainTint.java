package colorfullights.render;

import colorfullights.Config;
import colorfullights.compat.IrisCompat;
import colorfullights.compat.SableCompat;
import colorfullights.debug.Timings;
import colorfullights.light.ColorSampler;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.SectionPos;

/**
 * Tints block quads emitted by the vanilla section compiler (terrain without Sodium, and Sable ship plots).
 * <p>
 * Vertices there are section-relative, so we remember the origin of the section being compiled on this thread.
 * Outside section compilation (items, falling blocks, ...) consumers are passed through untouched.
 */
public final class VanillaTerrainTint {
    private static final ThreadLocal<TintingConsumer> CURRENT = new ThreadLocal<>();

    private VanillaTerrainTint() {
    }

    /**
     * Light of the world around a moving plot (a Sable ship), baked into its mesh: color multiplier in the low
     * 24 bits and block light level times 16 in bits 24-31, for a vertex at plot coordinates.
     */
    @FunctionalInterface
    public interface PlotLight {
        long at(float x, float y, float z);
    }

    public static void beginSection(SectionPos pos) {
        PlotLight plotLight = null;
        if (SableCompat.LOADED && SableCompat.isPlotSection(pos)) {
            // Without a shader pack, ship plots are tinted when drawn, by the world light where the ship is now.
            // Shader packs replace those shaders, so then the world light is baked into the ship's mesh instead
            // (rebuilt as the ship moves).
            if (!IrisCompat.shaderPackActive()) {
                CURRENT.remove();
                return;
            }
            plotLight = SableCompat.plotLight(pos);
        }
        CURRENT.set(new TintingConsumer(pos.minBlockX(), pos.minBlockY(), pos.minBlockZ(), plotLight));
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
        private final PlotLight plotLight;
        VertexConsumer delegate;

        TintingConsumer(int ox, int oy, int oz, PlotLight plotLight) {
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.plotLight = plotLight;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            long start = Timings.start();
            long shaded = ColorSampler.shadeARGB(ox + x, oy + y, oz + z, color, light);
            int c = ColorSampler.color(shaded), l = ColorSampler.light(shaded);
            if (plotLight != null) {
                long world = plotLight.at(ox + x, oy + y, oz + z);
                int tint = (int) world;
                int r = ((c >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
                int g = ((c >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
                int b = (c & 0xFF) * (tint & 0xFF) / 255;
                c = (c & 0xFF000000) | r << 16 | g << 8 | b;
                int worldBlock = Math.min(240, Math.round((int) (world >>> 24 & 0xFF) * Config.brightness));
                l = (l & 0xFFFF0000) | Math.max(l & 0xFFFF, worldBlock);
            }
            Timings.end(Timings.Part.MESH_SHADE, start);
            delegate.addVertex(x, y, z, c, u, v, overlay, l, nx, ny, nz);
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
