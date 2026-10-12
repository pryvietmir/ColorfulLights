package colorfullights.lamp;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/**
 * Settings of one placed directional lamp, packed into a long (0 = all defaults): a paint color (set with dyes),
 * a beam distance step and a focus step (set with a wrench). Kept per block position on the server
 * ({@link LampSavedData}) and mirrored to the clients, which read them from {@link #client}.
 */
public final class LampSettings {
    /** Beam distance multipliers a wrench cycles through; the default is the 1.0 step. */
    public static final float[] RANGES = {0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f};
    /** Focus multipliers (higher = narrower beam); the default is the 1.0 step. */
    public static final float[] FOCUSES = {0.5f, 0.75f, 1f, 1.5f, 2f, 3f};
    private static final int DEFAULT_RANGE = 3, DEFAULT_FOCUS = 2;

    // bits 0-23: color, bit 24: painted, bits 25-28: range step + 1 (0: default), bits 29-32: focus step + 1
    private static final long PAINTED = 1L << 24;

    private static final Long2LongOpenHashMap CLIENT = new Long2LongOpenHashMap();

    private LampSettings() {
    }

    /** The paint color (0xRRGGBB), or -1 if the lamp isn't painted. */
    public static int color(long v) {
        return (v & PAINTED) != 0 ? (int) (v & 0xFFFFFF) : -1;
    }

    public static float range(long v) {
        return RANGES[rangeStep(v)];
    }

    public static float focus(long v) {
        return FOCUSES[focusStep(v)];
    }

    public static int rangeStep(long v) {
        int s = (int) (v >> 25 & 15);
        return s == 0 ? DEFAULT_RANGE : s - 1;
    }

    public static int focusStep(long v) {
        int s = (int) (v >> 29 & 15);
        return s == 0 ? DEFAULT_FOCUS : s - 1;
    }

    public static long withColor(long v, int rgb) {
        return (v & ~(PAINTED | 0xFFFFFF)) | PAINTED | (rgb & 0xFFFFFF);
    }

    public static long withoutColor(long v) {
        return v & ~(PAINTED | 0xFFFFFF);
    }

    public static long nextRange(long v) {
        return withRangeStep(v, (rangeStep(v) + 1) % RANGES.length);
    }

    public static long nextFocus(long v) {
        return withFocusStep(v, (focusStep(v) + 1) % FOCUSES.length);
    }

    private static long withRangeStep(long v, int step) {
        return (v & ~(15L << 25)) | (step == DEFAULT_RANGE ? 0 : (long) (step + 1) << 25);
    }

    private static long withFocusStep(long v, int step) {
        return (v & ~(15L << 29)) | (step == DEFAULT_FOCUS ? 0 : (long) (step + 1) << 29);
    }

    // --- client mirror ---

    /** Settings of the lamp at a block position (0 if none). Client thread. */
    public static long client(long posKey) {
        return CLIENT.isEmpty() ? 0L : CLIENT.get(posKey);
    }

    /** Applies a payload from the server. */
    public static void receive(LampSettingsPayload p) {
        if (p.replace()) CLIENT.clear();
        for (int i = 0; i < p.keys().length; i++) {
            if (p.values()[i] == 0L) CLIENT.remove(p.keys()[i]);
            else CLIENT.put(p.keys()[i], p.values()[i]);
        }
    }

    /** On leaving a world or server. */
    public static void reset() {
        CLIENT.clear();
    }
}
