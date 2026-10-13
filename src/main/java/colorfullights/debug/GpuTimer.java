package colorfullights.debug;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/**
 * GPU time of a stretch of rendering, through GL timer queries, for {@link Timings} (only while timings are on).
 * Results are read a few frames later, when the GPU has finished, so nothing waits for it. Timer queries can't
 * nest: timed stretches must not overlap. Render thread only.
 */
public final class GpuTimer {
    private static final int RING = 4;

    private final Timings.Part part;
    private final int[] queries = new int[RING];
    private final boolean[] pending = new boolean[RING];
    private int next;
    private boolean running;

    public GpuTimer(Timings.Part part) {
        this.part = part;
    }

    public void begin() {
        if (!Timings.ENABLED || running) return;
        collect();
        if (pending[next]) return; // the GPU is far behind: skip this frame
        if (queries[next] == 0) queries[next] = GL15.glGenQueries();
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, queries[next]);
        running = true;
    }

    public void end() {
        if (!running) return;
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        pending[next] = true;
        next = (next + 1) % RING;
        running = false;
    }

    /** Adds the results that are ready. */
    private void collect() {
        for (int i = 0; i < RING; i++) {
            if (!pending[i]) continue;
            if (GL15.glGetQueryObjecti(queries[i], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) continue;
            Timings.add(part, GL33.glGetQueryObjecti64(queries[i], GL15.GL_QUERY_RESULT));
            pending[i] = false;
        }
    }
}
