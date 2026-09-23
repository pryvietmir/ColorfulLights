package colorfullights.render;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** Forwards to another consumer, multiplying every vertex color by a fixed tint (0xRRGGBB). */
public final class TintedVertexConsumer implements VertexConsumer {
    private VertexConsumer delegate;
    private int r = 255, g = 255, b = 255;

    public TintedVertexConsumer set(VertexConsumer delegate, int tint) {
        this.delegate = delegate;
        this.r = (tint >> 16) & 0xFF;
        this.g = (tint >> 8) & 0xFF;
        this.b = tint & 0xFF;
        return this;
    }

    @Override
    public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
        int cr = ((color >> 16) & 0xFF) * r / 255;
        int cg = ((color >> 8) & 0xFF) * g / 255;
        int cb = (color & 0xFF) * b / 255;
        delegate.addVertex(x, y, z, (color & 0xFF000000) | cr << 16 | cg << 8 | cb, u, v, overlay, light, nx, ny, nz);
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        delegate.addVertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        delegate.setColor(red * r / 255, green * g / 255, blue * b / 255, alpha);
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
