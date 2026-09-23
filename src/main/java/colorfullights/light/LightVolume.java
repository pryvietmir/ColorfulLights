package colorfullights.light;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Arrays;

/**
 * Visibility of a dynamic light within a 32³ block volume around it.
 * <p>
 * Light is flooded from the source cell through non-opaque blocks (26-connected, so it spreads diagonally too,
 * but never squeezes through diagonal gaps between solid blocks). A cell's visibility compares the length of the
 * path light has to travel with the straight-line distance: 1 where the light sees the cell directly, fading to 0
 * as the light has to bend further around corners, and 0 behind walls.
 */
public final class LightVolume {
    public static final int SIZE = 32;
    public static final int CELLS = SIZE * SIZE * SIZE;
    /** Index of the source cell on each axis; the volume spans source - 16 .. source + 15. */
    public static final int CENTER = 16;

    /** How many blocks of detour light may take around corners before it fades out completely. */
    private static final float BEND_TOLERANCE = 4f;
    /** Path cost units per block. */
    private static final int UNIT = 10;
    private static final int MAX_COST = 16 * UNIT + 60;
    private static final byte WALL = (byte) 0xFF;

    // Scratch buffers, reused between computations (client thread only).
    private static final byte[] OPACITY = new byte[CELLS];
    private static final int[] COST = new int[CELLS];
    private static final IntArrayList[] BUCKETS = new IntArrayList[MAX_COST + 1];

    static {
        for (int i = 0; i < BUCKETS.length; i++) BUCKETS[i] = new IntArrayList();
    }

    private static final int[][] OFFSETS;
    private static final int[] COSTS;
    /** Index offset of each neighbor, and of the single-axis steps a diagonal move passes (both within the grid). */
    private static final int[] DELTAS;
    private static final int[][] AXIS_DELTAS;
    /** Cells on the faces of the grid, whose neighbors need bounds checks. */
    private static final boolean[] BORDER = new boolean[CELLS];

    static {
        int n = 0;
        OFFSETS = new int[26][];
        COSTS = new int[26];
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    int axes = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (axes == 0) continue;
                    OFFSETS[n] = new int[]{dx, dy, dz};
                    COSTS[n] = axes == 1 ? 10 : axes == 2 ? 14 : 17; // ~ UNIT * sqrt(axes)
                    n++;
                }
        DELTAS = new int[26];
        AXIS_DELTAS = new int[26][];
        for (int i = 0; i < 26; i++) {
            int[] d = OFFSETS[i];
            DELTAS[i] = d[0] + SIZE * (d[1] + SIZE * d[2]);
            if (Math.abs(d[0]) + Math.abs(d[1]) + Math.abs(d[2]) == 1) {
                AXIS_DELTAS[i] = new int[0];
                continue;
            }
            IntArrayList steps = new IntArrayList();
            if (d[0] != 0) steps.add(d[0]);
            if (d[1] != 0) steps.add(SIZE * d[1]);
            if (d[2] != 0) steps.add(SIZE * SIZE * d[2]);
            AXIS_DELTAS[i] = steps.toIntArray();
        }
        for (int z = 0; z < SIZE; z++)
            for (int y = 0; y < SIZE; y++)
                for (int x = 0; x < SIZE; x++)
                    BORDER[index(x, y, z)] = x == 0 || y == 0 || z == 0 || x == SIZE - 1 || y == SIZE - 1 || z == SIZE - 1;
    }

    private LightVolume() {
    }

    public static int index(int x, int y, int z) {
        return x + SIZE * (y + SIZE * z);
    }

    /**
     * Computes visibility (0..255 per cell, x fastest, then y, then z) for a light of the given level in block
     * (cx, cy, cz). Cells beyond the light's reach are left at 0.
     * Must run on the client thread (it reuses scratch buffers).
     */
    public static byte[] compute(Level level, int cx, int cy, int cz, int lightLevel) {
        int ox = cx - CENTER, oy = cy - CENTER, oz = cz - CENTER;
        // no point flooding further than the light reaches (plus the detour allowance)
        int maxCost = Math.min(MAX_COST, (int) ((lightLevel + 1 + BEND_TOLERANCE) * UNIT));

        byte[] opacity = OPACITY;
        fillOpacity(level, ox, oy, oz, opacity);

        // Dial's algorithm: shortest path costs with small integer edge weights.
        int[] cost = COST;
        Arrays.fill(cost, Integer.MAX_VALUE);
        IntArrayList[] buckets = BUCKETS;
        for (IntArrayList b : buckets) b.clear();
        int start = index(CENTER, CENTER, CENTER);
        cost[start] = 0;
        buckets[0].add(start);

        for (int c = 0; c <= maxCost; c++) {
            IntArrayList bucket = buckets[c];
            for (int k = 0; k < bucket.size(); k++) {
                int cell = bucket.getInt(k);
                if (cost[cell] != c) continue; // stale entry
                boolean border = BORDER[cell];
                int x = 0, y = 0, z = 0;
                if (border) {
                    x = cell % SIZE;
                    y = (cell / SIZE) % SIZE;
                    z = cell / (SIZE * SIZE);
                }
                for (int i = 0; i < DELTAS.length; i++) {
                    if (border) {
                        int[] d = OFFSETS[i];
                        int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                        if (nx < 0 || ny < 0 || nz < 0 || nx >= SIZE || ny >= SIZE || nz >= SIZE) continue;
                    }
                    int next = cell + DELTAS[i];
                    byte o = opacity[next];
                    if (o == WALL) continue;
                    // no squeezing diagonally past solid blocks: every axis step on the way must be open
                    int[] axes = AXIS_DELTAS[i];
                    boolean open = true;
                    for (int a = 0; a < axes.length; a++) {
                        if (opacity[cell + axes[a]] == WALL) {
                            open = false;
                            break;
                        }
                    }
                    if (!open) continue;
                    int nc = c + COSTS[i] + o * UNIT;
                    if (nc > maxCost || nc >= cost[next]) continue;
                    cost[next] = nc;
                    buckets[nc].add(next);
                }
            }
        }

        byte[] vis = new byte[CELLS];
        for (int z = 0; z < SIZE; z++) {
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    int i = index(x, y, z);
                    if (cost[i] == Integer.MAX_VALUE) continue;
                    float dx = x - CENTER, dy = y - CENTER, dz = z - CENTER;
                    float straight = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    float detour = cost[i] / (float) UNIT - straight;
                    float v = 1f - Math.max(0f, detour) / BEND_TOLERANCE;
                    if (v > 0f) vis[i] = (byte) Math.round(Math.min(1f, v) * 255f);
                }
            }
        }
        return vis;
    }

    /**
     * Per-cell opacity: 0..14 = extra path cost (leaves, water...), WALL = blocks light (also unloaded chunks).
     * Reads block states section by section instead of looking up the chunk for every cell.
     */
    private static void fillOpacity(Level level, int ox, int oy, int oz, byte[] opacity) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minSection = level.getMinSection(), maxSection = level.getMaxSection();
        for (int sx = ox >> 4; sx <= (ox + SIZE - 1) >> 4; sx++) {
            for (int sz = oz >> 4; sz <= (oz + SIZE - 1) >> 4; sz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, false);
                for (int sy = oy >> 4; sy <= (oy + SIZE - 1) >> 4; sy++) {
                    int x0 = Math.max(ox, sx << 4), x1 = Math.min(ox + SIZE - 1, (sx << 4) + 15);
                    int y0 = Math.max(oy, sy << 4), y1 = Math.min(oy + SIZE - 1, (sy << 4) + 15);
                    int z0 = Math.max(oz, sz << 4), z1 = Math.min(oz + SIZE - 1, (sz << 4) + 15);
                    LevelChunkSection section = null;
                    byte fill;
                    if (sy < minSection || sy >= maxSection) {
                        fill = 0; // outside the build height: open air
                    } else if (chunk == null) {
                        fill = WALL;
                    } else {
                        section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
                        fill = 0;
                        if (section.hasOnlyAir()) section = null;
                    }
                    for (int z = z0; z <= z1; z++) {
                        for (int y = y0; y <= y1; y++) {
                            int row = index(0, y - oy, z - oz) - ox;
                            if (section == null) {
                                Arrays.fill(opacity, row + x0, row + x1 + 1, fill);
                                continue;
                            }
                            for (int x = x0; x <= x1; x++) {
                                BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                                int block = state.isAir() ? 0 : state.getLightBlock(level, pos.set(x, y, z));
                                opacity[row + x] = block >= 15 ? WALL : (byte) block;
                            }
                        }
                    }
                }
            }
        }
    }

    /** Visibility (0..1) of the cell containing a world position, or 0 outside the volume. */
    public static float sample(byte[] vis, int ox, int oy, int oz, int bx, int by, int bz) {
        int x = bx - ox, y = by - oy, z = bz - oz;
        if (x < 0 || y < 0 || z < 0 || x >= SIZE || y >= SIZE || z >= SIZE) return 0f;
        return (vis[index(x, y, z)] & 0xFF) / 255f;
    }
}
