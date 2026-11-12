package mtm.testkit.client;

import com.google.gson.JsonObject;

import java.util.Arrays;

/**
 * Frame times for performance tests (siding FPS, user 2026-11-13): {@link #nextFrame()} is called at the start of every
 * frame (GameRendererMixin), and {@link #measure(long)} records the gaps between frame starts for a while and sums them
 * up as average FPS, the 1% low FPS (frames per second at the 99th percentile frame time) and frame time percentiles.
 */
public final class FrameStats {

	private static final Object LOCK = new Object();
	private static long[] frames = new long[0];
	private static int count;
	private static boolean recording;
	private static long lastFrame;

	private FrameStats() {
	}

	public static void nextFrame() {
		final long now = System.nanoTime();
		synchronized (LOCK) {
			if (recording && lastFrame != 0) {
				if (count == frames.length) {
					frames = Arrays.copyOf(frames, Math.max(1024, frames.length * 2));
				}
				frames[count++] = now - lastFrame;
			}
			lastFrame = now;
		}
	}

	/**
	 * Records for the given time on the calling thread (not the render thread) and returns the sums.
	 */
	public static JsonObject measure(long millis) throws InterruptedException {
		synchronized (LOCK) {
			count = 0;
			lastFrame = 0;
			recording = true;
		}
		Thread.sleep(millis);
		final long[] gaps;
		synchronized (LOCK) {
			recording = false;
			gaps = Arrays.copyOf(frames, count);
		}
		final JsonObject result = new JsonObject();
		result.addProperty("frames", gaps.length);
		if (gaps.length == 0) {
			return result;
		}
		Arrays.sort(gaps);
		long sum = 0;
		for (final long gap : gaps) {
			sum += gap;
		}
		result.addProperty("fps", gaps.length * 1e9 / sum);
		result.addProperty("low1", 1e9 / percentile(gaps, 0.99));
		result.addProperty("p50ms", percentile(gaps, 0.5) / 1e6);
		result.addProperty("p90ms", percentile(gaps, 0.9) / 1e6);
		result.addProperty("p99ms", percentile(gaps, 0.99) / 1e6);
		result.addProperty("maxms", gaps[gaps.length - 1] / 1e6);
		return result;
	}

	private static double percentile(long[] sorted, double p) {
		return sorted[Math.min(sorted.length - 1, (int) Math.floor(p * sorted.length))];
	}
}
