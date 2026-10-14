package colorfullights.render;

import com.mojang.blaze3d.platform.GlStateManager;
import colorfullights.ColorfulLights;
import colorfullights.Config;
import colorfullights.compat.IrisCompat;
import colorfullights.debug.Timings;
import colorfullights.light.DynamicLights;
import colorfullights.light.LightVolume;
import colorfullights.light.SpotDepth;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-pixel dynamic lights in Sodium's terrain shader.
 * <p>
 * The chunk shaders are patched (see {@link #patch}) to evaluate up to {@link #MAX_LIGHTS} lights per fragment:
 * light level falls off with straight-line distance from the light's exact, per-frame interpolated position, and
 * is multiplied by the light's visibility volume (walls), sampled from a 3D texture with linear filtering, and for
 * spot lights by a smooth falloff across the edge of their cone.
 * Nothing is baked into meshes, so lights move smoothly without rebuilding chunks.
 * <p>
 * The visibility of each light is stored in its own 32-deep slab of one 32×32×(32·MAX_LIGHTS) R8 texture. Long-range
 * spot lights use a shadow map instead ({@link SpotDepth}), in their own 32-high slab of one 32×(32·MAX_LIGHTS) R16
 * texture.
 * Render thread only.
 */
public final class DynamicLightShader {
    public static final int MAX_LIGHTS = 16;
    /** How much of its block light a surface under the open sky loses at noon (see the fragment shader). */
    private static final float DAY_FADE = 0.9f;
    /** Texture unit for the visibility volume (a 3D target, so it doesn't clash with 2D textures on the unit). */
    public static final int TEXTURE_UNIT = 4;
    /** Texture unit for the spot light shadow maps. */
    public static final int DEPTH_TEXTURE_UNIT = 5;

    private static volatile boolean vertexPatched, fragmentPatched;
    /** Cached result of {@link #updateActive()}; read from chunk meshing threads. */
    private static volatile boolean active;

    private static int texture;
    private static final DynamicLights.Source[] SLABS = new DynamicLights.Source[MAX_LIGHTS];
    private static final byte[][] UPLOADED = new byte[MAX_LIGHTS][];
    private static ByteBuffer uploadBuffer;
    private static int depthTexture;
    private static final short[][] UPLOADED_DEPTH = new short[MAX_LIGHTS][];
    private static java.nio.ShortBuffer depthUploadBuffer;

    private static long preparedFrame = -1;
    private static long frame;
    static int count;
    static final float[] POSITIONS = new float[MAX_LIGHTS * 4];
    static final float[] COLORS = new float[MAX_LIGHTS * 4];
    static final float[] VOLUMES = new float[MAX_LIGHTS * 4];
    static final float[] DIRECTIONS = new float[MAX_LIGHTS * 4];
    static final float[] SPOTS = new float[MAX_LIGHTS * 4];
    static final float[] SHADOW_POS = new float[MAX_LIGHTS * 4];
    static final float[] SHADOW_DIR = new float[MAX_LIGHTS * 4];
    static final float[] PARAMS = new float[4];

    // per-frame scratch
    private static final List<DynamicLights.Source> CANDIDATES = new ArrayList<>();
    private static Vec3[] FRAME_POS = new Vec3[32];
    private static double[] DIST = new double[32];
    private static final boolean[] USED = new boolean[MAX_LIGHTS];
    private static final int[] SLAB_OF = new int[MAX_LIGHTS];

    private DynamicLightShader() {
    }

    /**
     * True when Sodium's (patched) terrain shaders draw the world: both were patched and no Iris shader pack is
     * replacing them.
     */
    public static boolean isActive() {
        return active;
    }

    /** Re-evaluates {@link #isActive()}; called once per client tick. Returns the new value. */
    public static boolean updateActive() {
        return active = vertexPatched && fragmentPatched && !IrisCompat.isShaderPackInUse();
    }

    public static void onFrameStart() {
        frame++;
    }

    // --- shader source patching ---

    private static final String VSH = "sodium:blocks/block_layer_opaque.vsh";
    private static final String FSH = "sodium:blocks/block_layer_opaque.fsh";

    /** Patches a Sodium shader source; returns it unchanged if it isn't a terrain shader or doesn't match. */
    public static String patch(String name, String source) {
        if (VSH.equals(name)) {
            String patched = patchVertex(source);
            vertexPatched = patched != null;
            return patched != null ? patched : source;
        }
        if (FSH.equals(name)) {
            String patched = patchFragment(source);
            fragmentPatched = patched != null;
            return patched != null ? patched : source;
        }
        return source;
    }

    private static String patchVertex(String src) {
        String decl = "out vec4 v_Color;";
        String assign = "v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);";
        if (!src.contains(decl) || !src.contains(assign) || !src.contains("vec3 position =")) {
            ColorfulLights.LOGGER.warn("Unexpected Sodium vertex shader; smooth dynamic lights disabled");
            return null;
        }
        return src
                .replace(decl, decl + """

                        out vec3 v_DynPos;   // camera-relative position
                        out vec4 v_DynBase;  // vertex color without the lightmap
                        out vec2 v_DynLight; // lightmap coordinates (block, sky)
                        """)
                .replace(assign, assign + """

                            v_DynPos = position;
                            v_DynBase = _vert_color;
                            v_DynLight = _vert_tex_light_coord;
                        """);
    }

    private static String patchFragment(String src) {
        String decl = "in vec4 v_Color; // The interpolated vertex color";
        String apply = "color *= v_Color; // Apply per-vertex color modulator";
        if (!src.contains(decl) || !src.contains(apply)) {
            ColorfulLights.LOGGER.warn("Unexpected Sodium fragment shader; smooth dynamic lights disabled");
            return null;
        }
        return src
                .replace(decl, decl + "\n" + FRAGMENT_LIGHTING.replace("MAX_LIGHTS", Integer.toString(MAX_LIGHTS))
                        .replace("DAY_FADE", Float.toString(DAY_FADE))
                        .replace("SPOT_RES", Integer.toString(SpotDepth.RES)))
                .replace(apply, "color *= _dyn_light(v_Color); // Apply per-vertex color modulator + dynamic lights");
    }

    private static final String FRAGMENT_LIGHTING = """

            in vec3 v_DynPos;
            in vec4 v_DynBase;
            in vec2 v_DynLight;

            uniform int u_DynCount;
            uniform vec4 u_DynPos[MAX_LIGHTS];    // xyz: camera-relative light position, w: light level
            uniform vec4 u_DynColor[MAX_LIGHTS];  // rgb: light color (brightest channel = 1), w: volume slab
            uniform vec4 u_DynVolume[MAX_LIGHTS]; // xyz: camera-relative origin of the visibility volume, w: cos of the outer cone
            uniform vec4 u_DynDir[MAX_LIGHTS];    // xyz: spot light direction, w: cos of the inner cone (plain light: 0, -1; outer -2)
            uniform vec4 u_DynSpot[MAX_LIGHTS];   // x: range of a long-range spot light (0: reach = level), y: shadow map slab (-1: none), z: tan of the outer half angle
            uniform vec4 u_DynShadowPos[MAX_LIGHTS]; // xyz: camera-relative origin the shadow map was computed at
            uniform vec4 u_DynShadowDir[MAX_LIGHTS]; // xyz: direction the shadow map was computed for
            uniform vec4 u_DynParams;             // x: intensity, y: sky reduction, z: brightness
            uniform sampler3D u_DynVis;
            uniform sampler2D u_DynDepth;
            uniform sampler2D u_LightTex;

            // Shadow map of a long-range spot light (see SpotDepth): 1 in front of the first wall towards p.
            float _dyn_shadow(int i, vec3 p) {
                // the map is looked up in the frame it was computed in, not the interpolated one of this frame
                vec3 dir = u_DynShadowDir[i].xyz;
                vec3 v = p - u_DynShadowPos[i].xyz;
                float fwd = dot(v, dir);
                if (fwd <= 1e-4) return 0.0;
                vec3 right = normalize(cross(dir, abs(dir.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
                vec3 up = cross(right, dir);
                vec2 uv = vec2(dot(v, right), dot(v, up)) / (fwd * u_DynSpot[i].z);
                // outside a stale map (the lamp turned since it was computed): no shadow known, so lit
                if (abs(uv.x) > 1.0 || abs(uv.y) > 1.0) return 1.0;
                vec2 t = clamp(uv * 0.5 + 0.5, vec2(0.5 / SPOT_RES.0), vec2(1.0 - 0.5 / SPOT_RES.0));
                float d = texture(u_DynDepth, vec2(t.x, (u_DynSpot[i].y + t.y) / MAX_LIGHTS.0)).r * u_DynSpot[i].x;
                // soft edge, widened by the world size of a texel (see SpotDepth.edge)
                float b = min(3.0, 0.75 * 2.0 * fwd * u_DynSpot[i].z / SPOT_RES.0);
                return clamp((d + 0.75 + b - length(v)) / (1.0 + 2.0 * b), 0.0, 1.0);
            }

            vec4 _dyn_light(vec4 lit) {
                // Daylight outshines lamps: under the open sky by day, block light (baked into the mesh, raised by
                // this mod's brightness) gives way to the day, so a torch never looks brighter than the sun around
                // it. In the shade, indoors and at night nothing changes.
                float daySky = u_DynParams.w * clamp(v_DynLight.y * 16.0 / 15.0, 0.0, 1.0);
                float block = v_DynLight.x;
                if (daySky > 0.001) {
                    block *= 1.0 - DAY_FADE * daySky;
                    lit = vec4(v_DynBase.rgb * texture(u_LightTex, vec2(block, v_DynLight.y)).rgb, lit.a);
                }
                if (u_DynCount == 0) return lit;

                // Face normal from screen-space derivatives, turned towards the camera: the lit side of the face.
                // Visibility is sampled half a block in front of the face, i.e. in the air the face looks into.
                vec3 n = normalize(cross(dFdx(v_DynPos), dFdy(v_DynPos)));
                if (dot(n, v_DynPos) > 0.0) n = -n;
                vec3 samplePos = v_DynPos + n * 0.5;

                float level = 0.0;
                vec3 colorSum = vec3(0.0);
                float weightSum = 0.0;
                for (int i = 0; i < u_DynCount; i++) {
                    vec3 toFrag = v_DynPos - u_DynPos[i].xyz;
                    float range = u_DynSpot[i].x;
                    // out of reach: skip before any square root or texture lookup
                    float reach = range > 0.0 ? range : u_DynPos[i].w;
                    float d2 = dot(toFrag, toFrag);
                    if (d2 >= reach * reach) continue;
                    float dist = sqrt(d2);
                    float lv = range > 0.0 ? u_DynPos[i].w * (1.0 - dist / range) : u_DynPos[i].w - dist;
                    if (lv <= 0.0) continue;
                    // spot cone (for plain lights smoothstep(-2, -1, c) = 1), and the spill right around the light
                    float c = dist > 1e-4 ? dot(toFrag, u_DynDir[i].xyz) / dist : 1.0;
                    lv *= max(smoothstep(u_DynVolume[i].w, u_DynDir[i].w, c), clamp(1.5 - dist, 0.0, 1.0));
                    if (lv <= 0.0) continue;
                    if (u_DynSpot[i].y >= 0.0) {
                        lv *= _dyn_shadow(i, samplePos);
                    } else {
                        vec3 cell = clamp(samplePos - u_DynVolume[i].xyz, vec3(0.5), vec3(31.5));
                        lv *= texture(u_DynVis, vec3(cell.xy / 32.0, (u_DynColor[i].w * 32.0 + cell.z) / (32.0 * MAX_LIGHTS.0))).r;
                    }
                    if (lv <= 0.0) continue;
                    level = max(level, lv);
                    float w = lv * lv;
                    colorSum += u_DynColor[i].rgb * w;
                    weightSum += w;
                }
                if (level <= 0.0) return lit;

                // daylight outshines lamps: under open sky they fade out as the day brightens
                float sky = clamp(v_DynLight.y * 16.0 / 15.0, 0.0, 1.0);
                level *= 1.0 - u_DynParams.w * sky;
                if (level <= 0.0) return lit;

                float dyn = min(level * u_DynParams.z, 15.0);
                float base = block * 16.0;

                vec3 tint = colorSum / weightSum;
                tint /= max(max(tint.r, tint.g), max(tint.b, 1e-4));
                // the dynamic light only colors the share of light it contributes
                float share = clamp(dyn / max(base, 1e-3), 0.0, 1.0);
                float strength = u_DynParams.x * min(1.0, dyn * (2.5 / 15.0)) * (1.0 - u_DynParams.y * sky) * share;
                vec3 mul = mix(vec3(1.0), tint, strength);
                // colored light darkens; make up for it with extra light, like the baked lighting does
                float kept = 0.5 * (mul.r + mul.g + mul.b) / 3.0 + 0.5 * dot(mul, vec3(0.2126, 0.7152, 0.0722));
                float eff = max(base, min(15.0, dyn * min(2.5, 1.0 / max(kept, 1e-3))));

                vec4 light = texture(u_LightTex, vec2(max(eff / 16.0, block), v_DynLight.y));
                return vec4(v_DynBase.rgb * light.rgb * mul, lit.a);
            }
            """;

    // --- per-frame state ---

    /** Collects this frame's lights and uploads visibility volumes. Called when a chunk shader is set up. */
    static void prepare() {
        if (preparedFrame == frame) return;
        preparedFrame = frame;
        long start = Timings.start();
        try {
            prepareFrame();
        } finally {
            Timings.end(Timings.Part.SHADER_FRAME, start);
        }
    }

    private static void prepareFrame() {
        Minecraft mc = Minecraft.getInstance();
        count = 0;
        PARAMS[0] = Config.intensity;
        PARAMS[1] = Config.skyReduction;
        PARAMS[2] = Config.brightness;
        PARAMS[3] = mc.level == null || !Config.enabled ? 0f : daylight(mc, mc.getTimer().getGameTimeDeltaPartialTick(false));
        if (!Config.enabled || !Config.dynamicLights || mc.level == null) return;

        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        float pt = mc.getTimer().getGameTimeDeltaPartialTick(false);

        // the selected lights (those that show most in the frame, see DynamicLights) with a visibility volume get a
        // slab, the nearest first (partial selection; there are few lights)
        List<DynamicLights.Source> candidates = CANDIDATES;
        candidates.clear();
        for (DynamicLights.Source s : DynamicLights.sources()) {
            if (s.selected && s.isReady() && s.level > 0 && s.color >= 0) candidates.add(s);
        }
        int m = candidates.size();
        if (FRAME_POS.length < m) {
            FRAME_POS = new Vec3[m * 2];
            DIST = new double[m * 2];
        }
        for (int i = 0; i < m; i++) {
            FRAME_POS[i] = candidates.get(i).framePosition(pt);
            DIST[i] = FRAME_POS[i].distanceToSqr(cam);
        }
        int n = Math.min(Math.min(MAX_LIGHTS, Config.maxDynamicLights), m);
        for (int k = 0; k < n; k++) {
            int best = k;
            for (int i = k + 1; i < m; i++) if (DIST[i] < DIST[best]) best = i;
            if (best != k) {
                java.util.Collections.swap(candidates, k, best);
                Vec3 p = FRAME_POS[k];
                FRAME_POS[k] = FRAME_POS[best];
                FRAME_POS[best] = p;
                double d = DIST[k];
                DIST[k] = DIST[best];
                DIST[best] = d;
            }
        }

        // keep slabs stable for lights that stay selected, so their volumes needn't be re-uploaded
        boolean[] used = USED;
        java.util.Arrays.fill(used, false);
        int[] slabOf = SLAB_OF;
        for (int k = 0; k < n; k++) {
            slabOf[k] = -1;
            DynamicLights.Source s = candidates.get(k);
            for (int slab = 0; slab < MAX_LIGHTS; slab++) {
                if (SLABS[slab] == s && !used[slab]) {
                    slabOf[k] = slab;
                    used[slab] = true;
                    break;
                }
            }
        }
        for (int k = 0; k < n; k++) {
            if (slabOf[k] >= 0) continue;
            for (int slab = 0; slab < MAX_LIGHTS; slab++) {
                if (!used[slab]) {
                    slabOf[k] = slab;
                    used[slab] = true;
                    SLABS[slab] = candidates.get(k);
                    UPLOADED[slab] = null;
                    UPLOADED_DEPTH[slab] = null;
                    break;
                }
            }
        }

        ensureTexture();
        for (int k = 0; k < n; k++) {
            DynamicLights.Source s = candidates.get(k);
            Vec3 p = FRAME_POS[k];
            int slab = slabOf[k];
            if (s.depth != null) {
                if (UPLOADED_DEPTH[slab] != s.depth) {
                    uploadDepth(slab, s.depth);
                    UPLOADED_DEPTH[slab] = s.depth;
                }
            } else if (UPLOADED[slab] != s.vis) {
                upload(slab, s.vis);
                UPLOADED[slab] = s.vis;
            }
            int i = count++ * 4;
            POSITIONS[i] = (float) (p.x - cam.x);
            POSITIONS[i + 1] = (float) (p.y - cam.y);
            POSITIONS[i + 2] = (float) (p.z - cam.z);
            POSITIONS[i + 3] = s.level;
            COLORS[i] = ((s.color >> 16) & 0xFF) / 255f;
            COLORS[i + 1] = ((s.color >> 8) & 0xFF) / 255f;
            COLORS[i + 2] = (s.color & 0xFF) / 255f;
            COLORS[i + 3] = slab;
            VOLUMES[i] = (float) (s.ox - cam.x);
            VOLUMES[i + 1] = (float) (s.oy - cam.y);
            VOLUMES[i + 2] = (float) (s.oz - cam.z);
            if (s.isSpot()) {
                Vec3 d = s.frameDirection(pt);
                DIRECTIONS[i] = (float) d.x;
                DIRECTIONS[i + 1] = (float) d.y;
                DIRECTIONS[i + 2] = (float) d.z;
            } else {
                DIRECTIONS[i] = DIRECTIONS[i + 1] = DIRECTIONS[i + 2] = 0f;
            }
            DIRECTIONS[i + 3] = s.cosInner;
            VOLUMES[i + 3] = s.cosOuter;
            boolean shadowMap = s.depth != null;
            SPOTS[i] = shadowMap ? s.range : 0f;
            SPOTS[i + 1] = shadowMap ? slab : -1f;
            SPOTS[i + 2] = shadowMap ? s.tanOuter : 0f;
            SPOTS[i + 3] = 0f;
            if (shadowMap) {
                SHADOW_POS[i] = (float) (s.sx - cam.x);
                SHADOW_POS[i + 1] = (float) (s.sy - cam.y);
                SHADOW_POS[i + 2] = (float) (s.sz - cam.z);
                SHADOW_DIR[i] = s.ddx;
                SHADOW_DIR[i + 1] = s.ddy;
                SHADOW_DIR[i + 2] = s.ddz;
            }
        }
    }

    /** How bright the day is, 0 (night) to 1 (clear noon), like vanilla's sky darkening; 0 without a sky. */
    private static float daylight(Minecraft mc, float pt) {
        if (!mc.level.dimensionType().hasSkyLight()) return 0f;
        float g = 1f - (net.minecraft.util.Mth.cos(mc.level.getTimeOfDay(pt) * ((float) Math.PI * 2f)) * 2f + 0.2f);
        g = 1f - net.minecraft.util.Mth.clamp(g, 0f, 1f);
        g *= 1f - mc.level.getRainLevel(pt) * 5f / 16f;
        g *= 1f - mc.level.getThunderLevel(pt) * 5f / 16f;
        return g;
    }

    /** Binds the visibility texture to {@link #TEXTURE_UNIT} and the shadow maps to {@link #DEPTH_TEXTURE_UNIT}. */
    static void bindTexture() {
        if (texture == 0) return;
        int previous = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + DEPTH_TEXTURE_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
        GlStateManager._activeTexture(previous);
    }

    private static void ensureTexture() {
        if (texture != 0) return;
        texture = GL11.glGenTextures();
        int previous = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R8, LightVolume.SIZE, LightVolume.SIZE,
                LightVolume.SIZE * MAX_LIGHTS, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GlStateManager._activeTexture(previous);
        uploadBuffer = MemoryUtil.memAlloc(LightVolume.CELLS);

        depthTexture = GL11.glGenTextures();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + DEPTH_TEXTURE_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R16, SpotDepth.RES, SpotDepth.RES * MAX_LIGHTS, 0,
                GL11.GL_RED, GL11.GL_UNSIGNED_SHORT, (java.nio.ShortBuffer) null);
        GlStateManager._activeTexture(previous);
        depthUploadBuffer = MemoryUtil.memAllocShort(SpotDepth.TEXELS);
    }

    private static void uploadDepth(int slab, short[] depth) {
        depthUploadBuffer.clear();
        depthUploadBuffer.put(depth).flip();
        int previous = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + DEPTH_TEXTURE_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 2);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, slab * SpotDepth.RES, SpotDepth.RES, SpotDepth.RES,
                GL11.GL_RED, GL11.GL_UNSIGNED_SHORT, depthUploadBuffer);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        GlStateManager._activeTexture(previous);
    }

    private static void upload(int slab, byte[] vis) {
        uploadBuffer.clear();
        uploadBuffer.put(vis).flip();
        int previous = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        // other code may leave unpack state behind (row length, skips, a bound unpack buffer)
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, slab * LightVolume.SIZE,
                LightVolume.SIZE, LightVolume.SIZE, LightVolume.SIZE, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, uploadBuffer);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        GlStateManager._activeTexture(previous);
    }
}
