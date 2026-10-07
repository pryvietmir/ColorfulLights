package colorfullights.render;

import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniform;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;

/** The dynamic light uniforms of one Sodium chunk shader program. Only loaded with Sodium. */
public final class SodiumDynamicLightUniforms {
    private final GlUniformInt count;
    private final Vec4Array positions;
    private final Vec4Array colors;
    private final Vec4Array volumes;
    private final Vec4Array directions;
    private final Vec4Array params;
    private final GlUniformInt visibility;

    private SodiumDynamicLightUniforms(ShaderBindingContext context) {
        count = context.bindUniformOptional("u_DynCount", GlUniformInt::new);
        positions = context.bindUniformOptional("u_DynPos", Vec4Array::new);
        colors = context.bindUniformOptional("u_DynColor", Vec4Array::new);
        volumes = context.bindUniformOptional("u_DynVolume", Vec4Array::new);
        directions = context.bindUniformOptional("u_DynDir", Vec4Array::new);
        params = context.bindUniformOptional("u_DynParams", Vec4Array::new);
        visibility = context.bindUniformOptional("u_DynVis", GlUniformInt::new);
    }

    /** Null if the program wasn't patched (e.g. the shader didn't match what we expected). */
    public static SodiumDynamicLightUniforms bind(ShaderBindingContext context) {
        SodiumDynamicLightUniforms u = new SodiumDynamicLightUniforms(context);
        return u.count != null && u.positions != null && u.colors != null && u.volumes != null
                && u.directions != null && u.params != null && u.visibility != null ? u : null;
    }

    /** Uploads this frame's lights; the program is bound. */
    public void setup() {
        DynamicLightShader.prepare();
        int n = DynamicLightShader.count;
        count.set(n);
        if (n == 0) return;
        positions.set(DynamicLightShader.POSITIONS, n);
        colors.set(DynamicLightShader.COLORS, n);
        volumes.set(DynamicLightShader.VOLUMES, n);
        directions.set(DynamicLightShader.DIRECTIONS, n);
        params.set(DynamicLightShader.PARAMS, 1);
        visibility.set(DynamicLightShader.TEXTURE_UNIT);
        DynamicLightShader.bindTexture();
    }

    /** A {@code vec4[]} uniform. */
    static final class Vec4Array extends GlUniform<float[]> {
        Vec4Array(int index) {
            super(index);
        }

        @Override
        public void set(float[] value) {
            GL20.glUniform4fv(index, value);
        }

        /** Uploads the first {@code elements} vec4s without allocating. */
        void set(float[] value, int elements) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer buf = stack.mallocFloat(elements * 4);
                buf.put(value, 0, elements * 4).flip();
                GL20.glUniform4fv(index, buf);
            }
        }
    }
}
