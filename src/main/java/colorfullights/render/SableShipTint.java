package colorfullights.render;

import foundry.veil.api.client.render.shader.processor.ShaderPreProcessor;
import foundry.veil.platform.VeilEventPlatform;
import colorfullights.ColorfulLights;
import io.github.ocelot.glslprocessor.api.GlslInjectionPoint;
import io.github.ocelot.glslprocessor.api.GlslParser;
import io.github.ocelot.glslprocessor.api.node.GlslNode;
import io.github.ocelot.glslprocessor.api.node.GlslTree;
import io.github.ocelot.glslprocessor.api.node.function.GlslFunctionNode;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL41;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Per-block light color on Sable sub-levels (Create Aeronautics ships).
 * <p>
 * Sable draws ships with Minecraft's terrain shaders, putting the ship's pose into {@code ModelViewMat}. A Veil
 * shader preprocessor extends those shaders to recover each vertex's world position and, from
 * {@link ObjectTintVolume}, tint it and light it at least as bright as the world's block light there (ships are
 * otherwise lit only by their own blocks and the sky). The tint is switched on only while Sable draws a ship, so other users of the
 * shaders are unaffected. Only loaded when Sable (and with it Veil) is installed. Render thread only.
 */
public final class SableShipTint {
    private static final Set<String> TERRAIN_SHADERS = Set.of(
            "rendertype_solid", "rendertype_cutout", "rendertype_cutout_mipped", "rendertype_translucent", "rendertype_tripwire");

    private static final List<String> DECLARATIONS = List.of(
            "uniform float ClEnabled;",
            "uniform mat4 ClInvView;",
            "uniform ivec3 ClCameraBlock;",
            "uniform vec3 ClCameraFrac;",
            "uniform sampler3D ClTintGrid;");

    private static final String MAIN_CODE = """
            vec4 cl_rel = ClInvView * (ModelViewMat * vec4(Position + ChunkOffset, 1.0));
            vec3 cl_world = cl_rel.xyz + ClCameraFrac;
            ivec3 cl_block = ivec3(floor(cl_world)) + ClCameraBlock;
            vec3 cl_texel = vec3(cl_block & ivec3(127, 63, 127)) + fract(cl_world);
            vec4 cl_cell = textureLod(ClTintGrid, cl_texel / vec3(128.0, 64.0, 128.0), 0.0);
            // colored light darkens; make up for it with extra light (up to 2x), like terrain lighting does
            float cl_boost = min(2.0, 3.0 / max(cl_cell.r + cl_cell.g + cl_cell.b, 0.001));
            float cl_blockLight = max(float(UV2.x), min(240.0, cl_cell.a * 240.0 * cl_boost));
            vec4 cl_oldLight = minecraft_sample_lightmap(Sampler2, UV2);
            vec4 cl_newLight = minecraft_sample_lightmap(Sampler2, ivec2(int(cl_blockLight), UV2.y));
            vertexColor.rgb *= mix(vec3(1.0), cl_cell.rgb * cl_newLight.rgb / max(cl_oldLight.rgb, vec3(0.001)), ClEnabled);
            """;

    /** Uniform locations per program: enabled, invView, cameraBlock, cameraFrac, grid. */
    private static final Int2ObjectOpenHashMap<int[]> LOCATIONS = new Int2ObjectOpenHashMap<>();
    private static final Matrix4f INV_VIEW = new Matrix4f();

    private SableShipTint() {
    }

    public static void register() {
        VeilEventPlatform.INSTANCE.onVeilAddShaderProcessors((provider, registry) -> registry.addPreprocessor(SableShipTint::modify, false));
    }

    private static void modify(ShaderPreProcessor.Context ctx, GlslTree tree) {
        if (!(ctx instanceof ShaderPreProcessor.MinecraftContext mc) || !ctx.isVertex()) return;
        if (!TERRAIN_SHADERS.contains(mc.shaderInstance())) return;
        if (tree.field("ChunkOffset").isEmpty() || tree.field("ModelViewMat").isEmpty() || tree.field("vertexColor").isEmpty()
                || tree.field("UV2").isEmpty() || tree.field("Sampler2").isEmpty()) return;
        Optional<GlslFunctionNode> main = tree.mainFunction();
        if (main.isEmpty()) return;
        try {
            for (String decl : DECLARATIONS) {
                tree.getBody().add(GlslInjectionPoint.AFTER_DECLARATIONS, GlslParser.parseExpression(decl));
            }
            List<GlslNode> code = GlslParser.parseExpressionList(MAIN_CODE);
            main.get().getBody().addAll(code);
            ColorfulLights.LOGGER.debug("Added ship light colors to shader {}", mc.shaderInstance());
        } catch (Exception e) {
            ColorfulLights.LOGGER.warn("Could not add ship light colors to shader {}", mc.shaderInstance(), e);
        }
    }

    /** Called when Sable starts drawing a ship with {@code shader}; {@code view} is the camera's model-view. */
    public static void begin(ShaderInstance shader, Matrix4f view) {
        int[] loc = locations(shader.getId());
        if (loc == null || !ObjectTintVolume.hasBoxes()) return;
        int program = shader.getId();
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        int bx = (int) Math.floor(cam.x), by = (int) Math.floor(cam.y), bz = (int) Math.floor(cam.z);
        view.invert(INV_VIEW);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer m = INV_VIEW.get(stack.mallocFloat(16));
            GL41.glProgramUniformMatrix4fv(program, loc[1], false, m);
        }
        GL41.glProgramUniform3i(program, loc[2], bx, by, bz);
        GL41.glProgramUniform3f(program, loc[3], (float) (cam.x - bx), (float) (cam.y - by), (float) (cam.z - bz));
        GL41.glProgramUniform1i(program, loc[4], ObjectTintVolume.TEXTURE_UNIT);
        GL41.glProgramUniform1f(program, loc[0], 1f);
        ObjectTintVolume.uploadAndBind();
    }

    /** Called when Sable is done drawing a ship. */
    public static void end(ShaderInstance shader) {
        int[] loc = locations(shader.getId());
        if (loc != null) GL41.glProgramUniform1f(shader.getId(), loc[0], 0f);
    }

    private static int[] locations(int program) {
        if (!GL.getCapabilities().OpenGL41) return null;
        int[] loc = LOCATIONS.get(program);
        if (loc == null) {
            loc = new int[]{
                    GL20.glGetUniformLocation(program, "ClEnabled"),
                    GL20.glGetUniformLocation(program, "ClInvView"),
                    GL20.glGetUniformLocation(program, "ClCameraBlock"),
                    GL20.glGetUniformLocation(program, "ClCameraFrac"),
                    GL20.glGetUniformLocation(program, "ClTintGrid")};
            LOCATIONS.put(program, loc);
        }
        return loc[0] < 0 ? null : loc;
    }

    /** Shader programs are recreated on resource reload. */
    public static void onReload() {
        LOCATIONS.clear();
    }
}
