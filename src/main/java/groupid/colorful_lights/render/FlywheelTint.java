package groupid.colorful_lights.render;

import groupid.colorful_lights.Colorful_lights;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.lwjgl.opengl.GL20;

/**
 * Per-pixel tint of Flywheel-rendered objects (Create contraptions) from {@link ObjectTintVolume}.
 * <p>
 * Flywheel's shared fragment code is patched so that fragments inside one of the volume's boxes are multiplied by
 * the light color at their world position and lit at least as bright as the dynamic light there. Everything else
 * Flywheel draws is left alone. Render thread only.
 */
public final class FlywheelTint {
    private static final String COMMON_FRAG = "flywheel:internal/common.frag";
    private static volatile boolean patched;

    /** Uniform locations per program handle: count, boxMin, boxMax, sampler. */
    private static final Int2ObjectOpenHashMap<int[]> LOCATIONS = new Int2ObjectOpenHashMap<>();

    private FlywheelTint() {
    }

    /** Whether Flywheel's shaders were patched; contraptions are then tinted per pixel. */
    public static boolean isActive() {
        return patched;
    }

    public static String patch(String name, String source) {
        if (!COMMON_FRAG.equals(name)) return source;
        String main = "void _flw_main() {";
        String light = "vec4 lightColor = vec4(1.);";
        if (!source.contains(main) || !source.contains(light)) {
            Colorful_lights.LOGGER.warn("Unexpected Flywheel shader; contraptions are tinted as a whole");
            patched = false;
            return source;
        }
        patched = true;
        return source
                .replace(main, FRAGMENT_TINT + "\n" + main)
                .replace(light, "_cl_tint(color);\n    " + light);
    }

    private static final String FRAGMENT_TINT = """
            uniform int _cl_boxCount;
            uniform ivec3 _cl_boxMin[16];
            uniform ivec3 _cl_boxMax[16];
            uniform sampler3D _cl_tintGrid; // rgb: light color multiplier, a: block light level / 15

            void _cl_tint(inout vec4 color) {
                if (_cl_boxCount == 0) return;
                vec3 p = flw_vertexPos.xyz;
                ivec3 block = ivec3(floor(p)) + flw_renderOrigin;
                bool inside = false;
                for (int i = 0; i < _cl_boxCount; i++) {
                    if (all(greaterThanEqual(block, _cl_boxMin[i])) && all(lessThanEqual(block, _cl_boxMax[i]))) {
                        inside = true;
                        break;
                    }
                }
                if (!inside) return;
                // world-addressed toroidal grid: block modulo the grid size, plus the position within the block
                vec3 texel = vec3(block & ivec3(127, 63, 127)) + fract(p);
                vec4 cell = texture(_cl_tintGrid, texel / vec3(128.0, 64.0, 128.0));
                color.rgb *= cell.rgb;
                flw_fragLight.x = max(flw_fragLight.x, cell.a * (15.0 / 16.0));
            }
            """;

    /** Sets the tint uniforms on a Flywheel program that was just bound. */
    public static void onProgramBound(int program) {
        if (!patched) return;
        int[] loc = LOCATIONS.get(program);
        if (loc == null) {
            loc = new int[]{
                    GL20.glGetUniformLocation(program, "_cl_boxCount"),
                    GL20.glGetUniformLocation(program, "_cl_boxMin"),
                    GL20.glGetUniformLocation(program, "_cl_boxMax"),
                    GL20.glGetUniformLocation(program, "_cl_tintGrid")};
            LOCATIONS.put(program, loc);
        }
        if (loc[0] < 0) return; // not a program with our fragment code (e.g. a compute shader)

        int n = ObjectTintVolume.boxCount;
        GL20.glUniform1i(loc[0], n);
        if (n == 0) return;
        GL20.glUniform3iv(loc[1], ObjectTintVolume.boxMinUniform);
        GL20.glUniform3iv(loc[2], ObjectTintVolume.boxMaxUniform);
        GL20.glUniform1i(loc[3], ObjectTintVolume.TEXTURE_UNIT);
        ObjectTintVolume.uploadAndBind();
    }

    /** GL may reuse a deleted program's handle; forget its cached locations. */
    public static void onProgramDeleted(int program) {
        LOCATIONS.remove(program);
    }
}
