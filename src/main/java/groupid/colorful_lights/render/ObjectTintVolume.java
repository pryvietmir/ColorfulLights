package groupid.colorful_lights.render;

import com.mojang.blaze3d.platform.GlStateManager;
import groupid.colorful_lights.Config;
import groupid.colorful_lights.compat.CreateCompat;
import groupid.colorful_lights.compat.SableCompat;
import groupid.colorful_lights.light.ColorSampler;
import groupid.colorful_lights.light.DynamicLights;
import groupid.colorful_lights.light.EmitterTracker;
import groupid.colorful_lights.light.LightColors;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-block light color around moving objects (Create contraptions, Sable ships), so they can be tinted block by
 * block.
 * <p>
 * A world-space grid of RGBA cells (rgb: color multiplier, a: block light level incl. dynamic light) is kept for
 * boxes around each object and addressed toroidally (world block modulo the grid size), so it needs no
 * re-centering. The grid is
 * mirrored into a 3D texture that the patched Flywheel shader samples per pixel; the CPU copy tints contraptions
 * drawn without Flywheel.
 * <p>
 * Client/render thread only, except {@link #sample}, which is called while rendering on the render thread too.
 */
public final class ObjectTintVolume {
    public static final int SX = 128, SY = 64, SZ = 128;
    public static final int MAX_BOXES = 16;
    /** Texture unit for the tint grid (3D target). */
    public static final int TEXTURE_UNIT = 13;
    /** Cells recomputed per tick at most. */
    private static final int CELL_BUDGET = 24_000;
    private static final double RANGE = 96;

    private static final byte[] CELLS = new byte[SX * SY * SZ * 4];

    /** Boxes (inclusive block bounds) whose cells are valid this tick. */
    static int boxCount;
    static final int[] BOX_MIN = new int[MAX_BOXES * 3];
    static final int[] BOX_MAX = new int[MAX_BOXES * 3];
    /** BOX_MIN / BOX_MAX trimmed to {@link #boxCount}, for uploading as uniform arrays. */
    static int[] boxMinUniform = new int[0], boxMaxUniform = new int[0];

    private static final List<int[]> PENDING_UPLOADS = new ArrayList<>();
    private static final List<int[]> LAST_BOXES = new ArrayList<>();
    private static int texture;
    private static ByteBuffer uploadBuffer;
    private static int tickCounter;
    private static long lastLightVersion;

    private ObjectTintVolume() {
    }

    private static int index(int x, int y, int z) {
        return (((z & (SZ - 1)) * SY + (y & (SY - 1))) * SX + (x & (SX - 1))) * 4;
    }

    // --- per tick (client thread) ---

    public static void tick(Minecraft mc) {
        boxCount = 0;
        if (!Config.enabled || mc.level == null || !LightColors.isReady() || !(CreateCompat.LOADED || SableCompat.LOADED)) {
            LAST_BOXES.clear();
            return;
        }
        tickCounter++;
        // Unmoved boxes are refreshed when light sources changed, and once a second for other light changes.
        long lightVersion = (long) EmitterTracker.version() << 32 | (DynamicLights.version() & 0xFFFFFFFFL);
        boolean refresh = lightVersion != lastLightVersion || tickCounter % 20 == 0;
        lastLightVersion = lightVersion;
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        List<AABB> objects = new ArrayList<>();
        if (CreateCompat.LOADED) CreateCompat.collectContraptionBounds(mc.level, cam, RANGE, objects);
        if (SableCompat.LOADED) SableCompat.collectBounds(mc.level, cam, RANGE, objects);

        int budget = CELL_BUDGET;
        List<int[]> boxes = new ArrayList<>();
        for (AABB bb : objects) {
            if (boxes.size() >= MAX_BOXES) break;
            int[] box = {
                    (int) Math.floor(bb.minX) - 2, (int) Math.floor(bb.minY) - 2, (int) Math.floor(bb.minZ) - 2,
                    (int) Math.floor(bb.maxX) + 2, (int) Math.floor(bb.maxY) + 2, (int) Math.floor(bb.maxZ) + 2};
            // the grid wraps around; larger boxes would overwrite themselves
            if (box[3] - box[0] >= SX || box[4] - box[1] >= SY || box[5] - box[2] >= SZ) continue;
            int cells = (box[3] - box[0] + 1) * (box[4] - box[1] + 1) * (box[5] - box[2] + 1);
            boolean moved = LAST_BOXES.stream().noneMatch(b -> java.util.Arrays.equals(b, box));
            if (moved || refresh) {
                if (cells > budget) {
                    // out of budget this tick: keep the box only if its cells were computed before
                    if (moved) continue;
                } else {
                    budget -= cells;
                    compute(mc.level, box);
                    PENDING_UPLOADS.add(box);
                }
            }
            boxes.add(box);
        }
        LAST_BOXES.clear();
        LAST_BOXES.addAll(boxes);
        for (int[] b : boxes) {
            System.arraycopy(b, 0, BOX_MIN, boxCount * 3, 3);
            System.arraycopy(b, 3, BOX_MAX, boxCount * 3, 3);
            boxCount++;
        }
        boxMinUniform = java.util.Arrays.copyOf(BOX_MIN, boxCount * 3);
        boxMaxUniform = java.util.Arrays.copyOf(BOX_MAX, boxCount * 3);
    }

    private static void compute(Level level, int[] box) {
        DynamicLights.Snapshot dynamic = DynamicLights.current();
        ColorSampler.Emitters emitters = ColorSampler.Emitters.around(level, box[0], box[1], box[2], box[3], box[4], box[5]);
        float[] scratch = new float[5];
        for (int z = box[2]; z <= box[5]; z++) {
            for (int y = box[1]; y <= box[4]; y++) {
                for (int x = box[0]; x <= box[3]; x++) {
                    float dyn = dynamic.isEmpty() ? 0f : DynamicLights.levelAt(dynamic, x + 0.5f, y + 0.5f, z + 0.5f, false);
                    int tint = ColorSampler.objectTint(level, x + 0.5, y + 0.5, z + 0.5, emitters, dyn, scratch);
                    int i = index(x, y, z);
                    CELLS[i] = (byte) (tint >> 16);
                    CELLS[i + 1] = (byte) (tint >> 8);
                    CELLS[i + 2] = (byte) tint;
                    // light the object gets there (world, extended emitter reach, dynamic), boosted like terrain light
                    CELLS[i + 3] = (byte) Math.round(Math.min(15f, scratch[4] * Config.brightness) / 15f * 255f);
                }
            }
        }
    }

    // --- queries ---

    /** True if the grid holds valid colors for moving objects this tick. */
    public static boolean hasBoxes() {
        return boxCount > 0;
    }

    /** Color multiplier (0xRRGGBB) at a world position, or white outside every box. Render thread. */
    public static int sample(double x, double y, double z) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        for (int b = 0; b < boxCount; b++) {
            if (bx >= BOX_MIN[b * 3] && by >= BOX_MIN[b * 3 + 1] && bz >= BOX_MIN[b * 3 + 2]
                    && bx <= BOX_MAX[b * 3] && by <= BOX_MAX[b * 3 + 1] && bz <= BOX_MAX[b * 3 + 2]) {
                int i = index(bx, by, bz);
                return (CELLS[i] & 0xFF) << 16 | (CELLS[i + 1] & 0xFF) << 8 | (CELLS[i + 2] & 0xFF);
            }
        }
        return 0xFFFFFF;
    }

    // --- GPU (render thread) ---

    /** Uploads cells computed since the last call and binds the grid texture to {@link #TEXTURE_UNIT}. */
    static void uploadAndBind() {
        int previous = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT);
        if (texture == 0) {
            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            // world-addressed: wrapping is what makes the toroidal layout seamless
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL11.GL_REPEAT);
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA8, SX, SY, SZ, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            uploadBuffer = MemoryUtil.memAlloc(SX * SY * 4 * 16);
        } else {
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        }
        if (!PENDING_UPLOADS.isEmpty()) {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
            GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
            for (int[] box : PENDING_UPLOADS) uploadBox(box);
            PENDING_UPLOADS.clear();
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        }
        GlStateManager._activeTexture(previous);
    }

    /** Uploads one box, split where it wraps around the grid edges. */
    private static void uploadBox(int[] box) {
        for (int z0 = box[2]; z0 <= box[5]; ) {
            int z1 = Math.min(box[5], z0 + (SZ - 1 - (z0 & (SZ - 1))));
            for (int y0 = box[1]; y0 <= box[4]; ) {
                int y1 = Math.min(box[4], y0 + (SY - 1 - (y0 & (SY - 1))));
                for (int x0 = box[0]; x0 <= box[3]; ) {
                    int x1 = Math.min(box[3], x0 + (SX - 1 - (x0 & (SX - 1))));
                    uploadRegion(x0, y0, z0, x1, y1, z1);
                    x0 = x1 + 1;
                }
                y0 = y1 + 1;
            }
            z0 = z1 + 1;
        }
    }

    private static void uploadRegion(int x0, int y0, int z0, int x1, int y1, int z1) {
        int w = x1 - x0 + 1, h = y1 - y0 + 1, d = z1 - z0 + 1;
        int bytes = w * h * d * 4;
        if (uploadBuffer.capacity() < bytes) {
            MemoryUtil.memFree(uploadBuffer);
            uploadBuffer = MemoryUtil.memAlloc(bytes);
        }
        uploadBuffer.clear();
        for (int z = z0; z <= z1; z++) {
            for (int y = y0; y <= y1; y++) {
                uploadBuffer.put(CELLS, index(x0, y, z), w * 4);
            }
        }
        uploadBuffer.flip();
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, x0 & (SX - 1), y0 & (SY - 1), z0 & (SZ - 1), w, h, d,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, uploadBuffer);
    }
}
