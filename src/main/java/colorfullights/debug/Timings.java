package colorfullights.debug;

import colorfullights.ColorfulLights;

import java.util.concurrent.atomic.LongAdder;

/**
 * Optional timing of the mod's work, enabled with {@code -Dcolorful_lights.timings=true}.
 * Every 10 seconds the time spent per part is logged, as milliseconds per second of game time.
 * When disabled, each probe costs a check of a static final flag.
 */
public final class Timings {
    public static final boolean ENABLED = Boolean.getBoolean("colorful_lights.timings");

    public enum Part {
        MESH_SHADE("tinting chunk mesh quads"),
        DYNAMIC_TICK("dynamic lights (per tick)"),
        LIGHT_VOLUME("  of which wall visibility volumes"),
        OBJECT_TINT("ship/contraption color grid"),
        WORLD_TINT("entity/particle colors (cache misses)"),
        SHADER_FRAME("dynamic light shader setup");

        final String label;
        final LongAdder nanos = new LongAdder();
        final LongAdder calls = new LongAdder();

        Part(String label) {
            this.label = label;
        }
    }

    private static final int REPORT_TICKS = 200;
    private static int ticks;

    private Timings() {
    }

    /** Start of a timed section; pass the result to {@link #end}. */
    public static long start() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void end(Part part, long start) {
        if (!ENABLED) return;
        part.nanos.add(System.nanoTime() - start);
        part.calls.increment();
    }

    /** Called every client tick; logs and resets the totals every {@link #REPORT_TICKS} ticks. */
    public static void tick() {
        if (!ENABLED || ++ticks < REPORT_TICKS) return;
        float seconds = ticks / 20f;
        ticks = 0;
        StringBuilder sb = new StringBuilder("Colorful Lights timings (ms per second, calls per second):");
        for (Part p : Part.values()) {
            long nanos = p.nanos.sumThenReset();
            long calls = p.calls.sumThenReset();
            sb.append(String.format("%n  %-40s %7.2f ms/s  %9.0f calls/s", p.label, nanos / 1e6 / seconds, calls / seconds));
        }
        ColorfulLights.LOGGER.info(sb.toString());
    }
}
