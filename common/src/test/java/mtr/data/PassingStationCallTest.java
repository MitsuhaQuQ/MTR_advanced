package mtr.data;

import mtr.block.BlockTrainScheduleSensor;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static mtr.block.BlockTrainScheduleSensor.TileEntityTrainScheduleSensor.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Timetable sensors and trains running through a platform without stopping (2026-11-12): such a train is told to the
 * platform as coming through, which "before arrival" fires for once; the sensor can take every train, only those
 * stopping or only those passing.
 */
class PassingStationCallTest {

	@Test
	void aPassingTrainTriggersBeforeArrivalOnceAndNothingElse() {
		final StationCall call = new StationCall(7, null, true);
		assertTrue(call.isPassing());
		call.approachMillis = 100_000;
		final BlockPos sensor = new BlockPos(1, 2, 3);
		assertFalse(call.trigger(sensor, StationCall.Trigger.BEFORE_ARRIVAL, 10, 80_000), "20 s before it comes through: not yet");
		assertTrue(call.trigger(sensor, StationCall.Trigger.BEFORE_ARRIVAL, 10, 92_000), "8 s before");
		assertFalse(call.trigger(sensor, StationCall.Trigger.BEFORE_ARRIVAL, 10, 95_000), "once only");
		assertTrue(call.trigger(new BlockPos(1, 2, 4), StationCall.Trigger.BEFORE_ARRIVAL, 10, 95_000), "another sensor fires too");
		// It never stands there
		assertFalse(call.trigger(new BlockPos(9, 9, 9), StationCall.Trigger.AFTER_ARRIVAL, 0, 120_000));
		assertFalse(call.trigger(new BlockPos(9, 9, 8), StationCall.Trigger.BEFORE_DEPARTURE, 60, 95_000));
	}

	@Test
	void aSensorTakesTheTrainsItIsSetFor() {
		assertTrue(BlockTrainScheduleSensor.TileEntityTrainScheduleSensor.matchesKind(KIND_ALL, true));
		assertTrue(BlockTrainScheduleSensor.TileEntityTrainScheduleSensor.matchesKind(KIND_ALL, false));
		assertTrue(BlockTrainScheduleSensor.TileEntityTrainScheduleSensor.matchesKind(KIND_STOPPING, false));
		assertFalse(BlockTrainScheduleSensor.TileEntityTrainScheduleSensor.matchesKind(KIND_STOPPING, true));
		assertTrue(BlockTrainScheduleSensor.TileEntityTrainScheduleSensor.matchesKind(KIND_PASSING, true));
		assertFalse(BlockTrainScheduleSensor.TileEntityTrainScheduleSensor.matchesKind(KIND_PASSING, false));
	}
}
