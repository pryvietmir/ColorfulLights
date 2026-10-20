package colorfullights.render;

import colorfullights.ColorfulLights;
import colorfullights.Config;
import colorfullights.debug.Timings;
import colorfullights.fog.FogDensity;
import colorfullights.light.DynamicLights;
import colorfullights.light.SpotDepth;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Beams of long-range spot lights ({@link DynamicLights.Source#isLongSpot()}: headlights, frontlights, also on
 * contraptions and ships) made visible in the air: light scattered towards the eye by haze, rain and snow.
 * <p>
 * After the level is drawn (and after an Iris shader pack's final pass, before the hand), a cone mesh is drawn for
 * each beam. For each pixel of its far side, the fragment shader walks the view ray through the cone in a few
 * dithered steps, up to the scene depth (nothing is drawn behind walls) and adds up the light scattered there:
 * light level fading with distance, the soft edge of the cone, the beam's shadow map ({@link SpotDepth}, so pillars
 * and leaves cast dark streaks into the beam) and a Henyey-Greenstein phase function (looking into the beam is
 * brighter than seeing it from the side). The result is added onto the frame, scaled by {@link FogDensity}.
 * <p>
 * Uses its own GL program and buffers, so it works the same with and without Sodium and under shader packs (Iris
 * keeps the scene depth in the main framebuffer's depth texture). The main framebuffer's color texture is drawn into
 * through a framebuffer of its own, so its depth texture can be read while drawing. Render thread only.
 */
public final class VolumetricBeams {
    public static final int MAX_BEAMS = 16;
    /** Sides of the cone mesh. */
    private static final int SEGMENTS = 32;
    /** Beams further than this from the camera aren't drawn. */
    private static final double MAX_DISTANCE = 160;
    /** ... and fade out over the last this many blocks before that. */
    private static final double FADE_DISTANCE = 48;
    /** Widest cone drawn (half angle); wider lamps are cut there. Cosine of 75 degrees. */
    static final float MIN_COS_OUTER = (float) Math.cos(Math.toRadians(75));
    /** Overall scale of the scattered light, tuned so a lamp at the default haze is a faint beam. */
    private static final float SCATTER = 0.35f;
    /** Texture units used while drawing (what was bound there is restored afterwards). */
    private static final int DEPTH_UNIT = 6, SHADOW_UNIT = 7;

    private static final String VERTEX = """
            #version 150
            in vec3 a_Pos;
            uniform mat4 u_ViewProj;
            uniform vec3 u_LightPos;   // camera-relative apex
            uniform vec3 u_Right;
            uniform vec3 u_Up;
            uniform vec3 u_Dir;
            uniform vec2 u_Size;       // x: radius of the cap, y: length
            out vec3 v_Pos;
            void main() {
                // (right, -up, dir) is right-handed (right x up = -dir), so the mesh's outward faces stay
                // counter-clockwise: from outside and from inside alike, the far side is back-facing
                vec3 p = u_LightPos + (a_Pos.x * u_Right - a_Pos.y * u_Up) * u_Size.x + a_Pos.z * u_Size.y * u_Dir;
                v_Pos = p;
                gl_Position = u_ViewProj * vec4(p, 1.0);
            }
            """;

    private static final String FRAGMENT = """
            #version 150
            in vec3 v_Pos;
            uniform sampler2D u_Depth;
            uniform sampler2D u_Shadow;
            uniform mat4 u_InvProj;
            uniform vec2 u_Screen;
            uniform vec3 u_LightPos;
            uniform vec3 u_Dir;
            uniform vec4 u_Cone;       // x: cos inner, y: cos outer, z: range, w: tan outer
            uniform vec4 u_ShadowPos;  // xyz: camera-relative origin of the shadow map, w: slab (-1: no shadows)
            uniform vec4 u_ShadowDir;  // xyz: direction of the shadow map, w: its tan outer
            uniform vec3 u_ShadowRight; // basis of the shadow map's rays (see SpotDepth.basis)
            uniform vec3 u_ShadowUp;
            uniform float u_Cap;       // length of the drawn cone: where the shadow map stops the whole beam
            uniform vec3 u_Color;      // light color times strength
            uniform int u_Steps;
            uniform float u_Noise;
            out vec4 fragColor;

            const float G = 0.55;

            float shadow(vec3 p) {
                if (u_ShadowPos.w < 0.0) return 1.0;
                vec3 dir = u_ShadowDir.xyz;
                vec3 v = p - u_ShadowPos.xyz;
                float fwd = dot(v, dir);
                if (fwd <= 1e-4) return 1.0;
                vec2 uv = vec2(dot(v, u_ShadowRight), dot(v, u_ShadowUp)) / (fwd * u_ShadowDir.w);
                if (abs(uv.x) > 1.0 || abs(uv.y) > 1.0) return 1.0;
                vec2 t = clamp(uv * 0.5 + 0.5, vec2(0.5 / SPOT_RES.0), vec2(1.0 - 0.5 / SPOT_RES.0));
                float d = texture(u_Shadow, vec2(t.x, (u_ShadowPos.w + t.y) / MAX_BEAMS.0)).r * u_Cone.z;
                float b = min(3.0, 0.75 * 2.0 * fwd * u_ShadowDir.w / SPOT_RES.0);
                return clamp((d + 0.75 + b - length(v)) / (1.0 + 2.0 * b), 0.0, 1.0);
            }

            // whether a point (relative to the apex) is inside the finite cone
            bool inside(vec3 q) {
                float f = dot(q, u_Dir);
                return f > 0.0 && f < u_Cap && f * f > dot(q, q) * u_Cone.y * u_Cone.y;
            }

            void main() {
                if (gl_FrontFacing) discard; // each pixel once: from the cone's far side

                vec3 rd = normalize(v_Pos);
                vec3 co = -u_LightPos; // camera relative to the apex
                vec3 v = u_Dir;
                float cos2 = u_Cone.y * u_Cone.y;
                float L = u_Cone.z;
                float cap = u_Cap;

                // where the ray crosses the cone's side and cap (the cone is convex: at most an entry and an exit);
                // no arrays, as dynamically indexed arrays are unreliable on some drivers
                const float NONE = 1e9;
                float h0 = NONE, h1 = NONE, h2 = NONE;
                float dv = dot(rd, v), cv = dot(co, v);
                float a = dv * dv - cos2;
                float b = 2.0 * (dv * cv - dot(rd, co) * cos2);
                float c = cv * cv - dot(co, co) * cos2;
                float disc = b * b - 4.0 * a * c;
                if (abs(a) > 1e-6 && disc >= 0.0) {
                    float s = sqrt(disc);
                    float r0 = (-b - s) / (2.0 * a), r1 = (-b + s) / (2.0 * a);
                    float f0 = cv + r0 * dv, f1 = cv + r1 * dv;
                    if (r0 > 0.0 && f0 >= 0.0 && f0 <= cap) h0 = r0;
                    if (r1 > 0.0 && f1 >= 0.0 && f1 <= cap) h1 = r1;
                }
                if (abs(dv) > 1e-6) {
                    float tc = (cap - cv) / dv;
                    vec3 q = co + rd * tc - v * cap;
                    if (tc > 0.0 && dot(q, q) <= cap * cap * u_Cone.w * u_Cone.w) h2 = tc;
                }
                // the two nearest crossings
                float first = min(h0, min(h1, h2));
                float second = max(min(h0, h1), min(max(h0, h1), h2));

                float tIn, tOut;
                if (inside(co)) {
                    tIn = 0.0;
                    tOut = first < NONE ? first : length(v_Pos);
                } else {
                    if (first >= NONE) discard;
                    tIn = first;
                    tOut = second < NONE ? second : length(v_Pos);
                }

                // nothing behind the scene
                vec2 uv = gl_FragCoord.xy / u_Screen;
                float depth = texture(u_Depth, uv).r;
                if (depth < 1.0) {
                    vec4 vp = u_InvProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                    tOut = min(tOut, length(vp.xyz / vp.w));
                }
                if (tOut <= tIn) discard;

                // dithered steps (interleaved gradient noise, shifted every frame)
                vec2 px = gl_FragCoord.xy + u_Noise;
                float jitter = fract(52.9829189 * fract(dot(px, vec2(0.06711056, 0.00583715))));
                // about a step per block of beam crossed, up to the configured number: short chords are cheap
                int steps = clamp(int(ceil((tOut - tIn) * 1.2)), 3, u_Steps);
                float dt = (tOut - tIn) / float(steps);
                float sum = 0.0;
                for (int i = 0; i < steps; i++) {
                    vec3 p = rd * (tIn + (float(i) + jitter) * dt);
                    vec3 toP = p - u_LightPos;
                    float dist = length(toP);
                    if (dist >= L || dist < 1e-3) continue;
                    vec3 l = toP / dist;
                    float cone = smoothstep(u_Cone.y, u_Cone.x, dot(l, v));
                    if (cone <= 0.0) continue;
                    // light fades with distance, and is densest right in front of the lens (the bright start of a beam)
                    float fade = (1.0 - dist / L) * (1.0 + 1.5 / (1.0 + 0.2 * dist * dist));
                    // Henyey-Greenstein towards the eye (relative to isotropic), mixed with some isotropic scattering
                    float cosT = dot(l, -rd);
                    float k = 1.0 + G * G - 2.0 * G * cosT;
                    float hg = (1.0 - G * G) / (k * sqrt(k));
                    float phase = 0.3 + 0.7 * hg;
                    sum += cone * fade * fade * phase * shadow(p);
                }
                sum *= dt;
                vec3 col = u_Color * (1.0 - exp(-sum));
                fragColor = vec4(col, 0.0);
            }
            """;

    private static boolean failed;
    private static int program, vao, vbo, vertexCount;
    private static int fbo, fboColor = -1, fboWidth, fboHeight;
    /** Quarter-size buffer for drawing the beams at half resolution, and the program adding it onto the frame. */
    private static int halfFbo, halfTexture, halfWidth, halfHeight;
    private static int compositeProgram, uCompositeTex;

    private static final String COMPOSITE_VERTEX = """
            #version 150
            out vec2 v_Uv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                v_Uv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String COMPOSITE_FRAGMENT = """
            #version 150
            in vec2 v_Uv;
            uniform sampler2D u_Tex;
            out vec4 fragColor;
            void main() {
                // many beams add up: compress the sum softly so standing among lamps glows but never turns white
                vec3 c = texture(u_Tex, v_Uv).rgb;
                float m = max(c.r, max(c.g, c.b));
                fragColor = vec4(c * (LIMIT / (LIMIT + m)), 0.0); // ~unchanged when faint, at most LIMIT
            }
            """;

    /** Brightest the beams together add to a pixel (they are compressed towards it). */
    private static final float LIMIT = 0.55f;

    /**
     * Makes sure the beam buffer fits a frame of width x height, at half resolution or full. False if it can't be
     * made.
     */
    private static boolean halfTarget(int width, int height, boolean half) {
        int w = half ? Math.max(1, (width + 1) / 2) : width, h = half ? Math.max(1, (height + 1) / 2) : height;
        if (halfFbo != 0 && w == halfWidth && h == halfHeight) return true;
        if (compositeProgram == 0) {
            int vs = compile(GL20.GL_VERTEX_SHADER, COMPOSITE_VERTEX),
                    fs = compile(GL20.GL_FRAGMENT_SHADER, COMPOSITE_FRAGMENT.replace("LIMIT", Float.toString(LIMIT)));
            if (vs == 0 || fs == 0) return false;
            int p = GL20.glCreateProgram();
            GL20.glAttachShader(p, vs);
            GL20.glAttachShader(p, fs);
            GL30.glBindFragDataLocation(p, 0, "fragColor");
            GL20.glLinkProgram(p);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                ColorfulLights.LOGGER.error("Visible lamp beams: composite shader failed: {}", GL20.glGetProgramInfoLog(p));
                GL20.glDeleteProgram(p);
                failed = true;
                return false;
            }
            compositeProgram = p;
            uCompositeTex = GL20.glGetUniformLocation(p, "u_Tex");
        }
        int previousActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SHADOW_UNIT);
        int previousTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (halfTexture == 0) halfTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, halfTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, w, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTex);
        GL13.glActiveTexture(previousActive);
        if (halfFbo == 0) halfFbo = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, halfFbo);
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, halfTexture, 0);
        int status = GlStateManager.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            ColorfulLights.LOGGER.error("Visible lamp beams: half resolution buffer incomplete ({}), drawing at full resolution", status);
            failed = true;
            return false;
        }
        halfWidth = w;
        halfHeight = h;
        return true;
    }
    private static int shadowTexture;
    private static ShortBuffer shadowUpload;
    private static final short[][] UPLOADED = new short[MAX_BEAMS][];
    private static int frame;

    // uniform locations
    private static int uViewProj, uLightPos, uRight, uUp, uDir, uSize, uDepth, uShadow, uInvProj, uScreen, uCone,
            uShadowPos, uShadowDir, uShadowRight, uShadowUp, uCap, uColor, uSteps, uNoise;

    record Beam(DynamicLights.Source source, Vec3 pos, Vec3 dir, float length, float fade) {
    }

    private static final List<Beam> BEAMS = new ArrayList<>();
    private static final colorfullights.debug.GpuTimer GPU = new colorfullights.debug.GpuTimer(Timings.Part.GPU_BEAMS);

    private VolumetricBeams() {
    }

    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        if (failed || !Config.enabled || !Config.volumetricBeams || !Config.spotLights || Config.beamIntensity <= 0f) return;
        long start = Timings.start();
        try {
            render(event);
        } catch (RuntimeException e) {
            failed = true;
            ColorfulLights.LOGGER.error("Visible lamp beams failed and are switched off", e);
        } finally {
            Timings.end(Timings.Part.BEAMS, start);
        }
    }

    private static void render(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float fog = FogDensity.get();
        if (fog <= 0f) return;

        Vec3 cam = event.getCamera().getPosition();
        float pt = mc.getTimer().getGameTimeDeltaPartialTick(false);
        BEAMS.clear();
        for (DynamicLights.Source s : DynamicLights.sources()) {
            if (!s.isLongSpot() || s.depth == null || s.color < 0 || s.level <= 0) continue;
            Vec3 p = s.framePosition(pt);
            Vec3 d = s.frameDirection(pt);
            // the beam only reaches as far as the shadow map lets any of it (a lamp facing a wall: a short cone)
            float length = Math.max(1f, Math.min(s.range, s.depthReach > 0f ? s.depthReach : s.range));
            float r = length * Math.min(4f, s.tanOuter);
            Vec3 mid = p.add(d.scale(length * 0.5));
            double toMid = mid.distanceTo(cam) - (r + length * 0.5);
            if (toMid > MAX_DISTANCE) continue;
            // the cone's own bounds, not the lamp's: a lamp behind the camera whose beam reaches into view is drawn
            AABB box = new AABB(mid, mid).inflate(r + length * 0.5);
            if (!event.getFrustum().isVisible(box)) continue;
            float fade = (float) Math.min(1.0, (MAX_DISTANCE - toMid) / FADE_DISTANCE);
            BEAMS.add(new Beam(s, p, d, length, fade));
        }
        if (BEAMS.isEmpty()) return;
        // the beams that show most in the frame first (see DynamicLights' score)
        BEAMS.sort(Comparator.comparingDouble((Beam b) -> -b.source().score));

        if (program == 0 && !init()) return;
        RenderTarget main = mc.getMainRenderTarget();
        if (!bindFramebuffer(main)) return;

        // dimmer in daylight under the open sky: the bright day outshines the scattered light
        float day = daylight(mc, pt);
        if (day > 0f) {
            int sky = mc.level.getBrightness(LightLayer.SKY, BlockPos.containing(cam));
            day *= sky / 15f;
        }
        float strength = SCATTER * Config.beamIntensity * fog * (1f - 0.8f * day);
        if (strength <= 0f) {
            main.bindWrite(true);
            return;
        }

        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        // Raw binds (and restoring exactly what was bound): other renderers bind textures behind GlStateManager's
        // back, so its cache of what is bound on a unit can't be trusted to skip a bind.
        int previousActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEPTH_UNIT);
        int previousDepthTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SHADOW_UNIT);
        int previousShadowTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        GL11.glViewport(0, 0, main.width, main.height);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        RenderSystem.colorMask(true, true, true, false);

        GlStateManager._glUseProgram(program);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEPTH_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, main.getDepthTextureId());
        // depth textures are sampled as plain values
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL30.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SHADOW_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, shadowTexture);

        Matrix4f proj = event.getProjectionMatrix();
        Matrix4f viewProj = new Matrix4f(proj).mul(event.getModelViewMatrix());
        Matrix4f invProj = new Matrix4f(proj).invert();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer m = stack.mallocFloat(16);
            GL20.glUniformMatrix4fv(uViewProj, false, viewProj.get(m));
            GL20.glUniformMatrix4fv(uInvProj, false, invProj.get(m));
        }
        GL20.glUniform1i(uDepth, DEPTH_UNIT);
        GL20.glUniform1i(uShadow, SHADOW_UNIT);
        // The beams go into a buffer of their own, added onto the frame afterwards with their sum compressed;
        // at half resolution by default (smooth light doesn't need every pixel, and standing inside a few beams
        // covers the whole screen several times).
        boolean half = halfTarget(main.width, main.height, Config.beamHalfResolution); // true: drawing into the beam buffer
        if (!half) bindFramebuffer(main); // making the buffer binds it
        int bw = half ? halfWidth : main.width, bh = half ? halfHeight : main.height;
        if (half) {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, halfFbo);
            GL11.glViewport(0, 0, bw, bh);
            GL30.glClearBufferfv(GL11.GL_COLOR, 0, new float[]{0f, 0f, 0f, 0f});
        }
        GL20.glUniform2f(uScreen, bw, bh);
        GL20.glUniform1i(uSteps, Config.beamSteps);
        frame = (frame + 1) & 63;
        GL20.glUniform1f(uNoise, frame * 5.588238f);
        GL30.glBindVertexArray(vao);

        int n = Math.min(MAX_BEAMS, BEAMS.size());
        GPU.begin();
        for (int k = 0; k < n; k++) {
            Beam beam = BEAMS.get(k);
            DynamicLights.Source s = beam.source();
            boolean shadows = s.depth != SpotDepth.OPEN;
            if (shadows && UPLOADED[k] != s.depth) {
                upload(k, s.depth);
                UPLOADED[k] = s.depth;
            }
            float cosOuter = Math.max(MIN_COS_OUTER, s.cosOuter);
            float cosInner = Math.max(cosOuter + 1e-3f, s.cosInner);
            float tanOuter = (float) Math.sqrt(1f - cosOuter * cosOuter) / cosOuter;
            float dx = (float) beam.dir().x, dy = (float) beam.dir().y, dz = (float) beam.dir().z;
            float[] basis = SpotDepth.basis(dx, dy, dz);
            float lx = (float) (beam.pos().x - cam.x), ly = (float) (beam.pos().y - cam.y), lz = (float) (beam.pos().z - cam.z);
            GL20.glUniform3f(uLightPos, lx, ly, lz);
            GL20.glUniform3f(uRight, basis[0], basis[1], basis[2]);
            GL20.glUniform3f(uUp, basis[3], basis[4], basis[5]);
            GL20.glUniform3f(uDir, dx, dy, dz);
            GL20.glUniform2f(uSize, beam.length() * tanOuter, beam.length());
            GL20.glUniform1f(uCap, beam.length());
            GL20.glUniform4f(uCone, cosInner, cosOuter, s.range, tanOuter);
            GL20.glUniform4f(uShadowPos, (float) (s.sx - cam.x), (float) (s.sy - cam.y), (float) (s.sz - cam.z), shadows ? k : -1f);
            GL20.glUniform4f(uShadowDir, s.ddx, s.ddy, s.ddz, s.tanOuter);
            float[] sb = SpotDepth.basis(s.ddx, s.ddy, s.ddz);
            GL20.glUniform3f(uShadowRight, sb[0], sb[1], sb[2]);
            GL20.glUniform3f(uShadowUp, sb[3], sb[4], sb[5]);
            float level = strength * s.level / 15f * beam.fade();
            GL20.glUniform3f(uColor, ((s.color >> 16) & 0xFF) / 255f * level, ((s.color >> 8) & 0xFF) / 255f * level,
                    (s.color & 0xFF) / 255f * level);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, vertexCount);
        }

        if (half) {
            // add the beams onto the frame (smoothly scaled up from half resolution)
            bindFramebuffer(main);
            GL11.glViewport(0, 0, main.width, main.height);
            GlStateManager._glUseProgram(compositeProgram);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + SHADOW_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, halfTexture);
            GL20.glUniform1i(uCompositeTex, SHADOW_UNIT);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        }

        // standing in a beam: glare
        if (!BeamGlare.failed()) {
            BeamGlare.draw(BEAMS, cam, event.getCamera().getLookVector(), viewProj, invProj, main.width, main.height,
                    DEPTH_UNIT, 1f - 0.7f * day);
        }
        GPU.end();
        Timings.counts(DynamicLights.sources().size(), n);
        GL30.glBindVertexArray(previousVao);
        GlStateManager._glUseProgram(previousProgram);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousShadowTex);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEPTH_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousDepthTex);
        GL13.glActiveTexture(previousActive);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        main.bindWrite(true);
    }

    /** Binds a framebuffer drawing into the main color texture (and nothing else). False if it can't be made. */
    private static boolean bindFramebuffer(RenderTarget main) {
        if (fbo == 0) fbo = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        int color = main.getColorTextureId();
        if (color != fboColor || main.width != fboWidth || main.height != fboHeight) {
            GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color, 0);
            int status = GlStateManager.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                ColorfulLights.LOGGER.error("Visible lamp beams: framebuffer incomplete ({}), switched off", status);
                failed = true;
                main.bindWrite(true);
                return false;
            }
            fboColor = color;
            fboWidth = main.width;
            fboHeight = main.height;
        }
        GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
        return true;
    }

    private static boolean init() {
        int vs = compile(GL20.GL_VERTEX_SHADER, VERTEX);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT.replace("SPOT_RES", Integer.toString(SpotDepth.RES))
                .replace("MAX_BEAMS", Integer.toString(MAX_BEAMS)));
        if (vs == 0 || fs == 0) {
            failed = true;
            return false;
        }
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GL20.glBindAttribLocation(p, 0, "a_Pos");
        GL30.glBindFragDataLocation(p, 0, "fragColor");
        GL20.glLinkProgram(p);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            ColorfulLights.LOGGER.error("Visible lamp beams: shader link failed, switched off: {}", GL20.glGetProgramInfoLog(p));
            GL20.glDeleteProgram(p);
            failed = true;
            return false;
        }
        program = p;
        uViewProj = GL20.glGetUniformLocation(p, "u_ViewProj");
        uLightPos = GL20.glGetUniformLocation(p, "u_LightPos");
        uRight = GL20.glGetUniformLocation(p, "u_Right");
        uUp = GL20.glGetUniformLocation(p, "u_Up");
        uDir = GL20.glGetUniformLocation(p, "u_Dir");
        uSize = GL20.glGetUniformLocation(p, "u_Size");
        uDepth = GL20.glGetUniformLocation(p, "u_Depth");
        uShadow = GL20.glGetUniformLocation(p, "u_Shadow");
        uInvProj = GL20.glGetUniformLocation(p, "u_InvProj");
        uScreen = GL20.glGetUniformLocation(p, "u_Screen");
        uCone = GL20.glGetUniformLocation(p, "u_Cone");
        uShadowPos = GL20.glGetUniformLocation(p, "u_ShadowPos");
        uShadowDir = GL20.glGetUniformLocation(p, "u_ShadowDir");
        uShadowRight = GL20.glGetUniformLocation(p, "u_ShadowRight");
        uShadowUp = GL20.glGetUniformLocation(p, "u_ShadowUp");
        uCap = GL20.glGetUniformLocation(p, "u_Cap");
        uColor = GL20.glGetUniformLocation(p, "u_Color");
        uSteps = GL20.glGetUniformLocation(p, "u_Steps");
        uNoise = GL20.glGetUniformLocation(p, "u_Noise");

        // unit cone: apex at the origin, cap at z = 1 with a radius circumscribing the unit circle
        float r = (float) (1 / Math.cos(Math.PI / SEGMENTS));
        FloatBuffer mesh = MemoryUtil.memAllocFloat(SEGMENTS * 6 * 3);
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = 2 * Math.PI * i / SEGMENTS, a1 = 2 * Math.PI * (i + 1) / SEGMENTS;
            float x0 = (float) Math.cos(a0) * r, y0 = (float) Math.sin(a0) * r;
            float x1 = (float) Math.cos(a1) * r, y1 = (float) Math.sin(a1) * r;
            // side, outward-facing (counter-clockwise seen from outside)
            mesh.put(0f).put(0f).put(0f).put(x1).put(y1).put(1f).put(x0).put(y0).put(1f);
            // cap
            mesh.put(0f).put(0f).put(1f).put(x0).put(y0).put(1f).put(x1).put(y1).put(1f);
        }
        mesh.flip();
        vertexCount = SEGMENTS * 6;
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, mesh, GL15.GL_STATIC_DRAW);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);
        GL30.glBindVertexArray(previousVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
        MemoryUtil.memFree(mesh);

        int previousActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        shadowTexture = GL11.glGenTextures();
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SHADOW_UNIT);
        int previousTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, shadowTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R16, SpotDepth.RES, SpotDepth.RES * MAX_BEAMS, 0,
                GL11.GL_RED, GL11.GL_UNSIGNED_SHORT, (ShortBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTex);
        GL13.glActiveTexture(previousActive);
        shadowUpload = MemoryUtil.memAllocShort(SpotDepth.TEXELS);
        ColorfulLights.LOGGER.info("Visible lamp beams ready");
        return true;
    }

    static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            ColorfulLights.LOGGER.error("Visible lamp beams: shader compile failed, switched off: {}", GL20.glGetShaderInfoLog(shader));
            GL20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    /** Uploads a shadow map into its slab; the shadow texture is bound on the active unit. */
    private static void upload(int slab, short[] depth) {
        shadowUpload.clear();
        shadowUpload.put(depth).flip();
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 2);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, slab * SpotDepth.RES, SpotDepth.RES, SpotDepth.RES,
                GL11.GL_RED, GL11.GL_UNSIGNED_SHORT, shadowUpload);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
    }

    /** How bright the day is, 0 (night) to 1 (clear noon); 0 without a sky. */
    private static float daylight(Minecraft mc, float pt) {
        if (!mc.level.dimensionType().hasSkyLight()) return 0f;
        float g = 1f - (Mth.cos(mc.level.getTimeOfDay(pt) * ((float) Math.PI * 2f)) * 2f + 0.2f);
        g = 1f - Mth.clamp(g, 0f, 1f);
        g *= 1f - colorfullights.compat.SimpleCloudsRain.rainAtCamera(mc, pt) * 5f / 16f; // Simple Clouds: local rain
        g *= 1f - mc.level.getThunderLevel(pt) * 5f / 16f;
        return g;
    }
}
