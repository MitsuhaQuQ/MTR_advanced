package transitsim.sim;

/**
 * An audio speaker's clips played one after another (0.70.0): each clip, then the gap set after it, then the next; after
 * the last, a loop waits its loop gap and starts again from the first, and a single play ends. The player says each tick
 * whether the clip it started is still playing; the sequence says which clip to start, if any.
 * <p>
 * A gap of 0 starts the next clip on the tick the last one is found ended.
 */
public final class ClipSequence {

	private final long[] gapTicks;
	private final boolean loop;
	private final long loopGapTicks;
	private int index;
	private long wait;
	private boolean playing;
	private boolean ended;

	/**
	 * @param gapTicks     the gap after each clip but the last, one per clip (the last one's is not used)
	 * @param loop         whether to start again after the last clip
	 * @param loopGapTicks the gap after the last clip before the first again, when looping
	 */
	public ClipSequence(long[] gapTicks, boolean loop, long loopGapTicks) {
		if (gapTicks.length == 0) {
			throw new IllegalArgumentException("No clips");
		}
		this.gapTicks = gapTicks.clone();
		this.loop = loop;
		this.loopGapTicks = Math.max(0, loopGapTicks);
	}

	/**
	 * @param clipPlaying whether the clip last started is still playing (ignored while none is)
	 * @return the clip to start now, or -1
	 */
	public int tick(boolean clipPlaying) {
		if (ended) {
			return -1;
		}
		if (playing) {
			if (clipPlaying) {
				return -1;
			}
			playing = false;
			final boolean last = index == gapTicks.length - 1;
			if (last && !loop) {
				ended = true;
				return -1;
			}
			wait = last ? loopGapTicks : Math.max(0, gapTicks[index]);
			index = last ? 0 : index + 1;
			// The gap counts from the next tick
			if (wait > 0) {
				return -1;
			}
		} else if (wait > 0) {
			wait--;
			if (wait > 0) {
				return -1;
			}
		}
		playing = true;
		return index;
	}

	/**
	 * @return whether a single play has played its last clip
	 */
	public boolean hasEnded() {
		return ended;
	}

	/**
	 * @return how long a single play of clips of the given lengths takes, with the gaps between them
	 */
	public static long totalTicks(long[] durationTicks, long[] gapTicks) {
		long total = 0;
		for (int i = 0; i < durationTicks.length; i++) {
			total += Math.max(0, durationTicks[i]);
			if (i < durationTicks.length - 1 && i < gapTicks.length) {
				total += Math.max(0, gapTicks[i]);
			}
		}
		return total;
	}
}
