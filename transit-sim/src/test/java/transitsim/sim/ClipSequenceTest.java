package transitsim.sim;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An audio speaker's clips one after another with their gaps (0.70.0): each clip plays for as long as the player says,
 * the gap after it is that many ticks of silence, and a loop waits its loop gap after the last.
 */
class ClipSequenceTest {

	/**
	 * Runs a sequence for some ticks, each clip playing for its length: @return the tick each clip started, as "tick:clip"
	 */
	private static List<String> run(ClipSequence sequence, int[] lengths, int ticks) {
		final List<String> started = new ArrayList<>();
		int playing = -1;
		int left = 0;
		for (int tick = 0; tick < ticks; tick++) {
			if (playing >= 0) {
				left--;
			}
			final int start = sequence.tick(playing >= 0 && left > 0);
			if (start >= 0) {
				started.add(tick + ":" + start);
				playing = start;
				left = lengths[start];
			}
		}
		return started;
	}

	@Test
	void clipsFollowEachOtherWithTheirGaps() {
		// Clip 0 plays 10 ticks then 4 ticks of silence; clip 1 plays 5 ticks with no gap; clip 2 plays 3 ticks; then ends
		final ClipSequence once = new ClipSequence(new long[]{4, 0, 99}, false, 50);
		assertEquals(Arrays.asList("0:0", "14:1", "19:2"), run(once, new int[]{10, 5, 3}, 100));
		assertTrue(once.hasEnded());
	}

	@Test
	void aLoopWaitsItsGapAfterTheLast() {
		final ClipSequence loop = new ClipSequence(new long[]{2, 0}, true, 6);
		// 0..3 clip 0, 2 silent, 5..7 clip 1, 6 silent, again
		assertEquals(Arrays.asList("0:0", "5:1", "13:0", "18:1", "26:0"), run(loop, new int[]{3, 2}, 27));
		assertFalse(loop.hasEnded());
		// One clip looping with no gap starts again on the tick it ends
		assertEquals(Arrays.asList("0:0", "4:0", "8:0"), run(new ClipSequence(new long[]{0}, true, 0), new int[]{4}, 9));
	}

	@Test
	void aSinglePlayTakesItsClipsAndTheGapsBetween() {
		assertEquals(10 + 4 + 5 + 3, ClipSequence.totalTicks(new long[]{10, 5, 3}, new long[]{4, 0, 99}));
		assertThrows(IllegalArgumentException.class, () -> new ClipSequence(new long[0], false, 0));
	}
}
