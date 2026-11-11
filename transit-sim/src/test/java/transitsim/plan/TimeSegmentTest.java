package transitsim.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TimeSegmentTest {

	/**
	 * A segment braking to a stop at 0.0125 blocks a tick a tick ends 40 blocks on; its acceleration is kept rounded to
	 * 0.013, which stops it 38.5 blocks on. At the stop, where the train stands, the time was not a number.
	 */
	@Test
	void theTimeAtTheEndOfABrakingSegmentIsANumber() {
		final TimeSegment segment = new TimeSegment(100, 1, 20, -1, 0.0125F);
		segment.endRailProgress = 140;
		final double time = segment.getTime(140);
		assertFalse(Double.isNaN(time));
		assertEquals(20 + 1 / 0.013, time, 0.01);
	}
}
