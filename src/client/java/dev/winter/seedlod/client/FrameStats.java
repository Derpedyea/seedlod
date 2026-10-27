package dev.winter.seedlod.client;

import java.util.Arrays;

/** Frame durations for the autopilot's {@code frames} line; off unless the autopilot is running. */
public final class FrameStats {
	static volatile boolean enabled;
	private static long[] frames = new long[1 << 16];
	private static int count;

	public static void record(long nanos) {
		if (!enabled || count >= frames.length) return;
		frames[count++] = nanos;
	}

	static void reset() {
		count = 0;
	}

	static String summary() {
		long[] f = Arrays.copyOf(frames, count);
		if (f.length == 0) return "no frames";
		Arrays.sort(f);
		long total = 0;
		int slow = 0;
		for (long n : f) {
			total += n;
			if (n > 50_000_000L) slow++;
		}
		return String.format("%d frames, avg %.1f fps, p50 %.1f ms, p99 %.1f ms, max %.1f ms, %d over 50 ms",
			f.length, f.length / (total / 1e9), f[f.length / 2] / 1e6, f[(int) (f.length * 0.99)] / 1e6, f[f.length - 1] / 1e6, slow);
	}
}
