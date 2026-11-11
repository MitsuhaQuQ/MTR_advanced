package transitsim.sim;

import org.junit.jupiter.api.Test;
import transitsim.motion.MotionConstants;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How long until a standing train's doors close, as the clients are told for their door sounds (0.65.30): from the stop
 * time, the time the doors take, and a hold until a planned departure.
 */
class DoorCloseTimeTest {

	@Test
	void theDoorsCloseWhenTheStopTimeLessTheirClosingIsReached() {
		final int total = 400;
		final int closeAt = total - MotionConstants.DOOR_DELAY - MotionConstants.DOOR_MOVE_TIME;
		assertEquals(closeAt, TrainRules.getTicksUntilDoorsClose(total, 0, 0, 1000));
		assertEquals(closeAt - 100, TrainRules.getTicksUntilDoorsClose(total, 100, 0, 1000));
		// Stood that long and still standing with nothing known to wait for: held for a way on or the rail ahead, so not known
		assertEquals(-1, TrainRules.getTicksUntilDoorsClose(total, closeAt - 1, 0, 1000));
		assertEquals(-1, TrainRules.getTicksUntilDoorsClose(total, closeAt + 5, 0, 1000));
		// A stop shorter than the doors take: the train is asked whether it may go from the start, so when the doors close
		// isn't known ahead, and the clients go by the doors themselves
		assertEquals(-1, TrainRules.getTicksUntilDoorsClose(60, 0, 0, 1000));
	}

	@Test
	void aHoldUntilAPlannedDepartureIsAddedAndAPastOneIsNot() {
		// Held, the train's stop time has nearly run out; the doors close once the hold ends
		final int total = 400;
		final float frozen = total - MotionConstants.DOOR_MOVE_TIME - MotionConstants.DOOR_DELAY - 1;
		assertEquals(1 + 30 * 20, TrainRules.getTicksUntilDoorsClose(total, frozen, 31000, 1000));
		// The hold over and still standing: held for something else now
		assertEquals(-1, TrainRules.getTicksUntilDoorsClose(total, frozen, 500, 1000));
		// A part of a tick counts as a whole one
		assertEquals(1 + 1, TrainRules.getTicksUntilDoorsClose(total, frozen, 1010, 1000));
	}
}
