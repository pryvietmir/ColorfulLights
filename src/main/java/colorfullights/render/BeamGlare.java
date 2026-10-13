package colorfullights.render;

import colorfullights.ColorfulLights;
import colorfullights.Config;
import colorfullights.light.DynamicLights;
import colorfullights.light.SpotDepth;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;

/**
 * Being inside a lamp's beam: the air around the camera glows in the beam's color, more so looking towards the
 * lamp, and a lamp shining straight at the camera dazzles with a bright spot on its lens. Nothing if the camera is
 * in the beam's shadow (behind a pillar) or the lens is hidden. The glare comes and goes over a fraction of a
 * second, like eyes adapting. Drawn by {@link VolumetricBeams} after the beams, into the same framebuffer, as one
 * full-screen pass per dazzling lamp. Render thread only.
 */
final class BeamGlare {
    /** Seconds for the glare to mostly follow a change (eye adaptation). */
    private static final float ADAPT = 0.35f;
    /** Most haze one lamp adds to the frame, and how bright its lens spot gets. */
    private static final float HAZE = 0.6f, SPOT = 1.8f;

    private static final String VERTEX = """
            #version 150
            void main() {
                // one triangle covering the screen
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String FRAGMENT = """
            #version 150
            uniform sampler2D u_Depth;
            uniform mat4 u_InvProj;
            uniform vec2 u_Screen;
            uniform vec4 u_Lamp;   // xy: lamp on screen (NDC), z: its distance, w: 1 if in front of the camera
            uniform vec3 u_Haze;   // color times haze amount
            uniform vec3 u_Spot;   // color times lens spot amount
            out vec4 fragColor;

            float sceneDistance(vec2 uv) {
                float depth = texture(u_Depth, uv).r;
                if (depth >= 1.0) return 1e9;
                vec4 vp = u_InvProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                return length(vp.xyz / vp.w);
            }

            void main() {
                vec2 ndc = gl_FragCoord.xy / u_Screen * 2.0 - 1.0;
                vec2 d = (ndc - u_Lamp.xy) * vec2(u_Screen.x / u_Screen.y, 1.0);
                float r2 = dot(d, d);
                // the air glows everywhere, brightest around the lamp
                vec3 col = u_Haze * (0.45 + 0.55 * exp(-r2 * 1.5));
                if (u_Lamp.w > 0.5 && r2 < 0.5) {
                    // the lens is seen unless something is in front of it (a block nearer than the lamp)
                    vec2 luv = u_Lamp.xy * 0.5 + 0.5;
                    float seen = 0.0;
                    vec2 px = 1.5 / u_Screen;
                    seen += step(u_Lamp.z - 0.8, sceneDistance(luv));
                    seen += step(u_Lamp.z - 0.8, sceneDistance(luv + vec2(px.x, 0.0)));
                    seen += step(u_Lamp.z - 0.8, sceneDistance(luv - vec2(px.x, 0.0)));
                    seen += step(u_Lamp.z - 0.8, sceneDistance(luv + vec2(0.0, px.y)));
                    seen += step(u_Lamp.z - 0.8, sceneDistance(luv - vec2(0.0, px.y)));
                    seen /= 5.0;
                    float spot = exp(-r2 * 600.0) * 2.0 + exp(-r2 * 40.0) * 0.5
                            + exp(-d.y * d.y * 3000.0) * exp(-abs(d.x) * 4.0) * 0.25; // a faint horizontal streak
                    col += u_Spot * seen * spot;
                }
                fragColor = vec4(col, 0.0);
            }
            """;

    private static boolean failed;
    private static int program;
    private static int uDepth, uInvProj, uScreen, uLamp, uHaze, uSpot;

    /** Smoothed glare per lamp (by source key): {haze, spot}, and when it was last seen. */
    private static final class State {
        float haze, spot;
        long frame;
    }

    private static final Long2ObjectOpenHashMap<State> STATES = new Long2ObjectOpenHashMap<>();
    private static long frame;
    private static long lastNanos;

    private BeamGlare() {
    }

    /**
     * Draws the glare of every beam the camera stands in. The caller has the beams' framebuffer bound, additive
     * blending on and the scene depth bound to {@code depthUnit}; the current program is restored by the caller.
     */
    static void draw(Iterable<VolumetricBeams.Beam> beams, Vec3 cam, Vector3f look, Matrix4f viewProj, Matrix4f invProj,
                     int width, int height, int depthUnit, float dimming) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.25f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        frame++;
        float follow = 1f - (float) Math.exp(-dt / ADAPT);
        boolean on = Config.beamGlare && Config.glareIntensity > 0f;

        boolean bound = false;
        // several lamps at once don't add up beyond about one lamp's haze
        float totalHaze = 0f;
        for (State st : STATES.values()) totalHaze += st.haze;
        float hazeScale = totalHaze > HAZE ? HAZE / totalHaze : 1f;
        for (VolumetricBeams.Beam beam : beams) {
            DynamicLights.Source s = beam.source();
            float[] target = on ? target(beam, cam, look, dimming) : null;
            State st = STATES.get(s.key);
            if (st == null) {
                if (target == null || (target[0] <= 0f && target[1] <= 0f)) continue;
                STATES.put(s.key, st = new State());
            }
            st.frame = frame;
            float th = target == null ? 0f : target[0], ts = target == null ? 0f : target[1];
            st.haze += (th - st.haze) * follow;
            st.spot += (ts - st.spot) * follow;
            if (st.haze < 1e-3f && st.spot < 1e-3f) continue;

            if (!bound) {
                if (program == 0 && !init()) return;
                GL20.glUseProgram(program);
                GL20.glUniform1i(uDepth, depthUnit);
                GL20.glUniform2f(uScreen, width, height);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    FloatBuffer m = stack.mallocFloat(16);
                    GL20.glUniformMatrix4fv(uInvProj, false, invProj.get(m));
                }
                bound = true;
            }
            Vec3 lamp = beam.pos();
            Vector4f clip = viewProj.transform(new Vector4f((float) (lamp.x - cam.x), (float) (lamp.y - cam.y),
                    (float) (lamp.z - cam.z), 1f));
            boolean front = clip.w > 0.05f;
            float nx = front ? clip.x / clip.w : 0f, ny = front ? clip.y / clip.w : 0f;
            GL20.glUniform4f(uLamp, nx, ny, (float) lamp.distanceTo(cam), front ? 1f : 0f);
            float r = ((s.color >> 16) & 0xFF) / 255f, g = ((s.color >> 8) & 0xFF) / 255f, b = (s.color & 0xFF) / 255f;
            float haze = st.haze * hazeScale;
            GL20.glUniform3f(uHaze, r * haze, g * haze, b * haze);
            GL20.glUniform3f(uSpot, r * st.spot, g * st.spot, b * st.spot);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        }
        // forget lamps that are gone
        STATES.values().removeIf(st -> st.frame != frame);
    }

    /** {haze, spot} the glare of a beam heads for, or null if the camera isn't in it. */
    private static float[] target(VolumetricBeams.Beam beam, Vec3 cam, Vector3f look, float dimming) {
        DynamicLights.Source s = beam.source();
        Vec3 p = beam.pos(), d = beam.dir();
        double vx = cam.x - p.x, vy = cam.y - p.y, vz = cam.z - p.z;
        double dist = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (dist < 0.3 || dist >= Math.min(s.range, beam.length() + 1f)) return null;
        float cos = (float) ((vx * d.x + vy * d.y + vz * d.z) / dist);
        float cosOuter = Math.max(VolumetricBeams.MIN_COS_OUTER, s.cosOuter);
        float cosInner = Math.max(cosOuter + 1e-3f, s.cosInner);
        float t = Math.max(0f, Math.min(1f, (cos - cosOuter) / (cosInner - cosOuter)));
        float cone = t * t * (3f - 2f * t);
        if (cone <= 0f) return null;
        // in the beam's shadow (a pillar between the lamp and the camera): no glare
        float lit = s.depth == SpotDepth.OPEN || s.basis == null ? 1f
                : SpotDepth.sample(s.depth, s.basis, s.tanOuter, s.range, (float) s.sx, (float) s.sy, (float) s.sz,
                s.ddx, s.ddy, s.ddz, (float) cam.x, (float) cam.y, (float) cam.z);
        if (lit <= 0f) return null;
        float fade = (float) (1.0 - dist / s.range);
        float strength = Config.glareIntensity * s.level / 15f * cone * lit * fade * fade * dimming;
        // looking towards the lamp
        float facing = (float) (-(look.x() * vx + look.y() * vy + look.z() * vz) / dist);
        float f = Math.max(0f, Math.min(1f, facing));
        float haze = HAZE * strength * (0.25f + 0.75f * f * f);
        float g = Math.max(0f, (facing - 0.75f) / 0.25f);
        float spot = SPOT * strength * g * g;
        return new float[]{haze, spot};
    }

    private static boolean init() {
        int vs = VolumetricBeams.compile(GL20.GL_VERTEX_SHADER, VERTEX);
        int fs = VolumetricBeams.compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT);
        if (vs == 0 || fs == 0) {
            failed = true;
            return false;
        }
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GL30.glBindFragDataLocation(p, 0, "fragColor");
        GL20.glLinkProgram(p);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            ColorfulLights.LOGGER.error("Beam glare: shader link failed, switched off: {}", GL20.glGetProgramInfoLog(p));
            GL20.glDeleteProgram(p);
            failed = true;
            return false;
        }
        program = p;
        uDepth = GL20.glGetUniformLocation(p, "u_Depth");
        uInvProj = GL20.glGetUniformLocation(p, "u_InvProj");
        uScreen = GL20.glGetUniformLocation(p, "u_Screen");
        uLamp = GL20.glGetUniformLocation(p, "u_Lamp");
        uHaze = GL20.glGetUniformLocation(p, "u_Haze");
        uSpot = GL20.glGetUniformLocation(p, "u_Spot");
        return true;
    }

    static boolean failed() {
        return failed;
    }
}
