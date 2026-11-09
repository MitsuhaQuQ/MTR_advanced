package mtr.data;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import transitsim.motion.PlatformDwell;

import static org.junit.jupiter.api.Assertions.*;

class StationCallTest {

	@Test
	void anApproachFiresOnceEvenWhenTheEstimateMovesOutsideAndBackIntoTheWindow() {
		final StationCall call = new StationCall(1, null);
		final BlockPos announcer = BlockPos.ZERO;
		call.approachMillis = 18000;
		assertFalse(call.trigger(announcer, StationCall.Trigger.BEFORE_ARRIVAL, 15, 1000));
		call.approachMillis = 14000;
		assertTrue(call.trigger(announcer, StationCall.Trigger.BEFORE_ARRIVAL, 15, 2000));
		call.approachMillis = 24000;
		assertFalse(call.trigger(announcer, StationCall.Trigger.BEFORE_ARRIVAL, 15, 3000));
		call.approachMillis = 12000;
		assertFalse(call.trigger(announcer, StationCall.Trigger.BEFORE_ARRIVAL, 15, 4000));
	}

	@Test
	void dwellCountsFromActualArrivalAndDepartureWaitsForClearance() {
		final StationCall call = new StationCall(1, null);
		call.dwell = PlatformDwell.update(null, 1, 3000, -1);
		assertFalse(call.trigger(BlockPos.ZERO, StationCall.Trigger.AFTER_ARRIVAL, 5, 7999));
		assertTrue(call.trigger(BlockPos.ZERO, StationCall.Trigger.AFTER_ARRIVAL, 5, 8000));
		final BlockPos departureBell = new BlockPos(1, 0, 0);
		assertFalse(call.trigger(departureBell, StationCall.Trigger.BEFORE_DEPARTURE, 5, 8000));
		call.dwell = PlatformDwell.update(call.dwell, 1, 9000, 60);
		assertEquals(3000, call.dwell.arrivedMillis);
		assertTrue(call.trigger(departureBell, StationCall.Trigger.BEFORE_DEPARTURE, 5, 9000));
		assertFalse(call.trigger(departureBell, StationCall.Trigger.BEFORE_DEPARTURE, 5, 9100));
	}
}
