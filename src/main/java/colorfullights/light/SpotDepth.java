package colorfullights.light;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Shadow map of a long-range spot light: for {@link #RES}² rays spread over the light's cone, the distance to the
 * first block that stops light. Used instead of a {@link LightVolume} for spot lights that reach further than the
 * 32³ volume.
 * <p>
 * Rays are laid out on the plane one block in front of the light: texel (u, v) in [-1, 1]² points along
 * {@code dir + (u * right + v * up) * tanOuter}. The basis is the same in the Sodium shader ({@link #basis}).
 * Distances are stored as fractions of the light's range (65535 = nothing hit).
 */
public final class SpotDepth {
    public static final int RES = 64;
    public static final int TEXELS = RES * RES;

    /** A map without walls (shadows switched off). */
    public static final short[] OPEN = new short[TEXELS];

    static {
        java.util.Arrays.fill(OPEN, (short) 0xFFFF);
    }

    private SpotDepth() {
    }

    /**
     * The right and up vectors {rx, ry, rz, ux, uy, uz} perpendicular to a unit direction. In GLSL:
     * {@code right = normalize(cross(dir, abs(dir.y) < 0.99 ? vec3(0, 1, 0) : vec3(1, 0, 0))); up = cross(right, dir);}
     */
    public static float[] basis(float dx, float dy, float dz) {
        float hx, hy, hz;
        if (Math.abs(dy) < 0.99f) {
            hx = 0f; hy = 1f; hz = 0f;
        } else {
            hx = 1f; hy = 0f; hz = 0f;
        }
        // right = dir x helper
        float rx = dy * hz - dz * hy, ry = dz * hx - dx * hz, rz = dx * hy - dy * hx;
        float len = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
        rx /= len; ry /= len; rz /= len;
        // up = right x dir
        float ux = ry * dz - rz * dy, uy = rz * dx - rx * dz, uz = rx * dy - ry * dx;
        return new float[]{rx, ry, rz, ux, uy, uz};
    }

    /** Casts the cone's rays from (x, y, z) through the client world. Client thread. */
    public static short[] compute(Level level, double x, double y, double z, float dx, float dy, float dz,
                                  float tanOuter, float range) {
        float[] b = basis(dx, dy, dz);
        short[] depth = new short[TEXELS];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        cacheCx = cacheCz = Integer.MIN_VALUE;
        cacheChunk = null;
        cacheSection = null;
        cacheSy = Integer.MIN_VALUE;
        minY = level.getMinBuildHeight();
        maxY = level.getMaxBuildHeight();
        for (int j = 0; j < RES; j++) {
            float v = (j + 0.5f) / RES * 2f - 1f;
            for (int i = 0; i < RES; i++) {
                float u = (i + 0.5f) / RES * 2f - 1f;
                // the corners of the map are outside the (round) cone: nothing to find there
                if (u * u + v * v > EDGE2) {
                    depth[j * RES + i] = (short) 0xFFFF;
                    continue;
                }
                float rx = dx + (u * b[0] + v * b[3]) * tanOuter;
                float ry = dy + (u * b[1] + v * b[4]) * tanOuter;
                float rz = dz + (u * b[2] + v * b[5]) * tanOuter;
                float len = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
                float hit = march(level, pos, x, y, z, rx / len, ry / len, rz / len, range);
                depth[j * RES + i] = (short) Math.round(Math.min(1f, hit / range) * 65535f);
            }
        }
        return depth;
    }

    /** Squared radius in map coordinates beyond which texels are outside the cone (with a texel of margin). */
    private static final float EDGE2 = (1f + 2f / RES) * (1f + 2f / RES);

    /** How far the beam gets before walls stop all of it: the furthest distance in the map (at most range). */
    public static float reach(short[] depth, float range) {
        int max = 0;
        for (int j = 0; j < RES && max < 0xFFFF; j++) {
            float v = (j + 0.5f) / RES * 2f - 1f;
            for (int i = 0; i < RES; i++) {
                float u = (i + 0.5f) / RES * 2f - 1f;
                if (u * u + v * v > EDGE2) continue; // corners are always open
                max = Math.max(max, depth[j * RES + i] & 0xFFFF);
            }
        }
        return Math.min(range, max / 65535f * range + 1.5f);
    }

    /** Distance along a unit ray to the first cell whose block stops light (range if none). Amanatides-Woo DDA. */
    private static float march(Level level, BlockPos.MutableBlockPos pos, double ox, double oy, double oz,
                               float dx, float dy, float dz, float range) {
        int x = (int) Math.floor(ox), y = (int) Math.floor(oy), z = (int) Math.floor(oz);
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        float tDeltaX = dx == 0 ? Float.MAX_VALUE : Math.abs(1f / dx);
        float tDeltaY = dy == 0 ? Float.MAX_VALUE : Math.abs(1f / dy);
        float tDeltaZ = dz == 0 ? Float.MAX_VALUE : Math.abs(1f / dz);
        float tMaxX = dx == 0 ? Float.MAX_VALUE : (float) ((dx > 0 ? x + 1 - ox : ox - x) * tDeltaX);
        float tMaxY = dy == 0 ? Float.MAX_VALUE : (float) ((dy > 0 ? y + 1 - oy : oy - y) * tDeltaY);
        float tMaxZ = dz == 0 ? Float.MAX_VALUE : (float) ((dz > 0 ? z + 1 - oz : oz - z) * tDeltaZ);
        float t = 0f;
        while (t < range) {
            // step into the next cell (the light's own cell is never a wall)
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
            }
            if (t >= range) break;
            if (blocksLight(level, pos, x, y, z)) return t;
        }
        return range;
    }

    // The chunk and section the march is in, so most cells need no chunk lookup (client thread only).
    private static int cacheCx, cacheCz, cacheSy, minY, maxY;
    private static LevelChunk cacheChunk;
    private static LevelChunkSection cacheSection;

    /**
     * Same rule as {@link LightVolume}: blocks with full light opacity, and unloaded chunks. Everything that doesn't
     * stop light (grass, flowers, leaves, glass, ...) is transparent to the beam.
     */
    private static boolean blocksLight(Level level, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        if (y < minY || y >= maxY) return false;
        int cx = x >> 4, cz = z >> 4;
        if (cx != cacheCx || cz != cacheCz) {
            cacheChunk = level.getChunkSource().getChunk(cx, cz, false);
            cacheCx = cx;
            cacheCz = cz;
            cacheSy = Integer.MIN_VALUE;
        }
        if (cacheChunk == null) return true;
        int sy = y >> 4;
        if (sy != cacheSy) {
            cacheSection = cacheChunk.getSection(cacheChunk.getSectionIndexFromSectionY(sy));
            cacheSy = sy;
        }
        if (cacheSection.hasOnlyAir()) return false;
        BlockState state = cacheSection.getBlockState(x & 15, y & 15, z & 15);
        return !state.isAir() && state.getLightBlock(level, pos.set(x, y, z)) >= 15;
    }

    /**
     * Visibility (0..1) of a point for a spot light at (px, py, pz) pointing along dir: 1 in front of the first
     * wall along the ray towards the point, fading to 0 within a block behind it, 0 outside the map.
     */
    public static float sample(short[] depth, float[] basis, float tanOuter, float range,
                               float px, float py, float pz, float dx, float dy, float dz,
                               float x, float y, float z) {
        float vx = x - px, vy = y - py, vz = z - pz;
        float fwd = vx * dx + vy * dy + vz * dz;
        if (fwd <= 1e-4f) return 0f;
        float u = (vx * basis[0] + vy * basis[1] + vz * basis[2]) / (fwd * tanOuter);
        float v = (vx * basis[3] + vy * basis[4] + vz * basis[5]) / (fwd * tanOuter);
        if (Math.abs(u) > 1f || Math.abs(v) > 1f) return 1f; // outside a stale map: no shadow known
        // bilinear, texel centers at (i + 0.5) / RES
        float fx = Math.max(0f, Math.min(RES - 1f, (u * 0.5f + 0.5f) * RES - 0.5f));
        float fy = Math.max(0f, Math.min(RES - 1f, (v * 0.5f + 0.5f) * RES - 0.5f));
        int i0 = (int) fx, j0 = (int) fy;
        int i1 = Math.min(RES - 1, i0 + 1), j1 = Math.min(RES - 1, j0 + 1);
        float ax = fx - i0, ay = fy - j0;
        float d00 = (depth[j0 * RES + i0] & 0xFFFF), d10 = (depth[j0 * RES + i1] & 0xFFFF);
        float d01 = (depth[j1 * RES + i0] & 0xFFFF), d11 = (depth[j1 * RES + i1] & 0xFFFF);
        float d = ((d00 * (1 - ax) + d10 * ax) * (1 - ay) + (d01 * (1 - ax) + d11 * ax) * ay) / 65535f * range;
        float dist = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        return edge(d, dist, fwd, tanOuter);
    }

    /**
     * Visibility from the distance to the first wall (d) and to the point (dist): a ramp over about a block, widened
     * by the world size of a texel there (2 * fwd * tanOuter / RES), so that when the map is recomputed and its rays
     * land elsewhere, shadow edges shift as a soft change instead of flipping between lit and dark. Same in the shader.
     */
    public static float edge(float d, float dist, float fwd, float tanOuter) {
        float b = Math.min(3f, 0.75f * 2f * fwd * tanOuter / RES);
        return Math.max(0f, Math.min(1f, (d + 0.75f + b - dist) / (1f + 2f * b)));
    }
}
