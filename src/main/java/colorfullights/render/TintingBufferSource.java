package colorfullights.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;

/**
 * Wraps a buffer source so that every vertex color is multiplied by the light color at the vertex, from
 * {@link ObjectTintVolume}. Vertices must be camera-relative (as for entity rendering). Render thread only.
 */
public final class TintingBufferSource implements MultiBufferSource {
    private final MultiBufferSource delegate;
    private final Vec3 camera;

    public TintingBufferSource(MultiBufferSource delegate, Vec3 camera) {
        this.delegate = delegate;
        this.camera = camera;
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        return new Consumer(delegate.getBuffer(type), camera);
    }

    private static final class Consumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final double cx, cy, cz;
        private float x, y, z;

        Consumer(VertexConsumer delegate, Vec3 camera) {
            this.delegate = delegate;
            this.cx = camera.x;
            this.cy = camera.y;
            this.cz = camera.z;
        }

        private int tint() {
            return ObjectTintVolume.sample(cx + x, cy + y, cz + z);
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            this.x = x;
            this.y = y;
            this.z = z;
            int t = tint();
            int r = ((color >> 16) & 0xFF) * ((t >> 16) & 0xFF) / 255;
            int g = ((color >> 8) & 0xFF) * ((t >> 8) & 0xFF) / 255;
            int b = (color & 0xFF) * (t & 0xFF) / 255;
            delegate.addVertex(x, y, z, (color & 0xFF000000) | r << 16 | g << 8 | b, u, v, overlay, light, nx, ny, nz);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            int t = tint();
            delegate.setColor(r * ((t >> 16) & 0xFF) / 255, g * ((t >> 8) & 0xFF) / 255, b * (t & 0xFF) / 255, a);
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
