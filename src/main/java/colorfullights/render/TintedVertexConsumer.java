package colorfullights.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.jetbrains.annotations.Nullable;

/**
 * Forwards to another consumer, multiplying every vertex color by a fixed tint (0xRRGGBB).
 * <p>
 * When the consumer came from a buffer source, the buffer can be ended under it: drawing another render type into a
 * shared buffer ends the current one (GeckoLib renderers do this mid-model, e.g. JEGN guns drawing the player's arm).
 * GeckoLib then fetches a fresh buffer, but only if it can see a {@link BufferBuilder}; behind this wrapper it
 * can't, so the wrapper refreshes the buffer itself at the start of every vertex.
 */
public final class TintedVertexConsumer implements VertexConsumer {
    private VertexConsumer delegate;
    @Nullable
    private MultiBufferSource source;
    @Nullable
    private RenderType type;
    private int r = 255, g = 255, b = 255;

    public TintedVertexConsumer set(VertexConsumer delegate, int tint) {
        this.delegate = delegate;
        this.source = null;
        this.type = null;
        return tint(tint);
    }

    /** Wraps the buffer of a render type, re-fetching it from the source if it gets ended. */
    public TintedVertexConsumer set(MultiBufferSource source, RenderType type, int tint) {
        this.delegate = source.getBuffer(type);
        this.source = source;
        this.type = type;
        return tint(tint);
    }

    private TintedVertexConsumer tint(int tint) {
        this.r = (tint >> 16) & 0xFF;
        this.g = (tint >> 8) & 0xFF;
        this.b = tint & 0xFF;
        return this;
    }

    private void refresh() {
        if (source != null && delegate instanceof BufferBuilder builder && !builder.building) {
            delegate = source.getBuffer(type);
        }
    }

    @Override
    public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
        refresh();
        int cr = ((color >> 16) & 0xFF) * r / 255;
        int cg = ((color >> 8) & 0xFF) * g / 255;
        int cb = (color & 0xFF) * b / 255;
        delegate.addVertex(x, y, z, (color & 0xFF000000) | cr << 16 | cg << 8 | cb, u, v, overlay, light, nx, ny, nz);
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        refresh();
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
