package mtr.data;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which blocks a train sets off sensors over between two ticks (G3).
 */
class SensorSweepTest {

	@Test
	void everyBlockPassedIsCheckedWhenTheServerIsSlow() {
		// 2 blocks a tick, 5 ticks at once: 10 blocks passed in one go
		final List<Double> swept = TrainServer.getSweptRailProgresses(100, 110, 2, 5);
		assertEquals(20, swept.size());
		assertEquals(100, swept.get(0));
		assertTrue(swept.get(swept.size() - 1) < 110);
		for (int i = 1; i < swept.size(); i++) {
			assertEquals(0.5, swept.get(i) - swept.get(i - 1), 1E-9);
		}
	}

	@Test
	void nothingIsSweptWhenTheTrainHardlyMovedMovedBackOrJumped() {
		// The blocks around the front are checked as before, so a short move needs nothing more
		assertTrue(TrainServer.getSweptRailProgresses(100, 100.8, 1, 1).isEmpty());
		// A new path or a loop starting again
		assertTrue(TrainServer.getSweptRailProgresses(100, 20, 1, 1).isEmpty());
		// Put somewhere else, much further than it could run
		assertTrue(TrainServer.getSweptRailProgresses(100, 400, 2, 1).isEmpty());
		// Not checked before
		assertTrue(TrainServer.getSweptRailProgresses(Double.NaN, 110, 2, 5).isEmpty());
	}
}
