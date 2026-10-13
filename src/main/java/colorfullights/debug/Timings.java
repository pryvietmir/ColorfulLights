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
        SHADER_FRAME("dynamic light shader setup"),
        BEAMS("visible lamp beams (CPU side)"),
        SPOT_DEPTH("  of which beam shadow maps"),
        LAMP_COLLECT("  of which finding placed lamps"),
        TINT_MISS("terrain tint cache misses (count)"),
        SERVER_BEAMS("server beam lights (per tick)"),
        GPU_TERRAIN("GPU: sky..translucent (terrain, entities)"),
        GPU_BEAMS("GPU: visible lamp beams");

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

    /** Counts an event without timing it. */
    public static void count(Part part) {
        if (ENABLED) part.calls.increment();
    }

    /** Adds time measured elsewhere (e.g. on the GPU). */
    public static void add(Part part, long nanos) {
        if (!ENABLED) return;
        part.nanos.add(nanos);
        part.calls.increment();
    }

    // frame times, measured between frame starts (render thread)
    private static long lastFrame, frames, frameNanos, worstFrame;
    private static final java.util.concurrent.atomic.AtomicInteger SOURCES = new java.util.concurrent.atomic.AtomicInteger();
    private static int beams;

    /** Called at the start of every frame. */
    public static void frame() {
        if (!ENABLED) return;
        long now = System.nanoTime();
        if (lastFrame != 0) {
            long d = now - lastFrame;
            frames++;
            frameNanos += d;
            if (d > worstFrame) worstFrame = d;
        }
        lastFrame = now;
    }

    /** Current numbers of dynamic light sources and of beams drawn, for the report. */
    public static void counts(int sources, int beamsDrawn) {
        if (!ENABLED) return;
        SOURCES.set(sources);
        if (beamsDrawn >= 0) beams = beamsDrawn;
    }

    public static void end(Part part, long start) {
        if (!ENABLED) return;
        part.nanos.add(System.nanoTime() - start);
        part.calls.increment();
    }

    private static final GpuTimer TERRAIN_GPU = new GpuTimer(Part.GPU_TERRAIN);

    /** GPU time of the level from the sky to the weather (timings only). */
    public static void onRenderStage(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (event.getStage() == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_SKY) TERRAIN_GPU.begin();
        else if (event.getStage() == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_WEATHER) TERRAIN_GPU.end();
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
        if (frames > 0) {
            sb.append(String.format("%n  frames: %.1f fps, %.2f ms average, %.2f ms worst; %d dynamic lights, %d beams drawn",
                    frames / (frameNanos / 1e9), frameNanos / 1e6 / frames, worstFrame / 1e6, SOURCES.get(), beams));
        }
        frames = frameNanos = worstFrame = 0;
        ColorfulLights.LOGGER.info(sb.toString());
    }
}
