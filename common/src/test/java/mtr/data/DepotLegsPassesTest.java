package mtr.data;

import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which platform a way out of a depot runs through just before turning back into its first stop (6号线, 2026-11-12), for
 * the duty planner's first come, first out at such a terminus.
 */
class DepotLegsPassesTest {

	private static PathData rail(int startZ, int endZ, RailType railType, long savedRailBaseId, int dwellTime) {
		final BlockPos start = new BlockPos(0, 64, startZ);
		final BlockPos end = new BlockPos(0, 64, endZ);
		final Rail rail = new Rail(start, RailAngle.fromAngle(startZ < endZ ? 90 : 270), end, RailAngle.fromAngle(startZ < endZ ? 270 : 90), railType, TransportMode.TRAIN);
		return new PathData(rail, savedRailBaseId, dwellTime, start, end, 0);
	}

	@Test
	void throughTheTerminusPlatformAndItsTurnBack() {
		// Out of the siding along the line, through platform 2 without stopping, into the turn back beyond it, back out and
		// into platform 3, the first stop
		assertEquals(2, DepotLegs.passedBeforeTurning(Arrays.asList(
				rail(-100, 0, RailType.SIDING, 9, 0),
				rail(0, 20, RailType.PLATFORM, 2, 0),
				rail(20, 60, RailType.TURN_BACK, 0, 1),
				rail(60, 20, RailType.TURN_BACK, 0, 0),
				rail(20, 0, RailType.PLATFORM, 3, 40)
		), 3).savedRailBaseId);
		// A way out runs through platforms carrying no platform: the rail itself, for the platform there to be found
		assertEquals(new BlockPos(0, 64, 0), DepotLegs.passedBeforeTurning(Arrays.asList(
				rail(-100, 0, RailType.SIDING, 9, 0),
				rail(0, 20, RailType.PLATFORM, 0, 0),
				rail(20, 60, RailType.TURN_BACK, 0, 1),
				rail(60, 20, RailType.TURN_BACK, 0, 0),
				rail(20, 0, RailType.PLATFORM, 3, 40)
		), 3).startingPos);
	}

	@Test
	void aMeasuredWayOutEndsAtItsPlatform() {
		// A depot way as measured: no stop times, the platform last
		assertEquals(new BlockPos(0, 64, 0), DepotLegs.passedBeforeTurningInto(Arrays.asList(
				rail(-100, 0, RailType.SIDING, 0, 0),
				rail(0, 20, RailType.PLATFORM, 0, 0),
				rail(20, 60, RailType.TURN_BACK, 0, 0),
				rail(60, 20, RailType.TURN_BACK, 0, 0),
				rail(20, 0, RailType.PLATFORM, 3, 0)
		)).startingPos);
	}

	@Test
	void straightIntoTheFirstStopPassesNothing() {
		org.junit.jupiter.api.Assertions.assertNull(DepotLegs.passedBeforeTurning(Arrays.asList(
				rail(-100, 0, RailType.SIDING, 9, 0),
				rail(0, 20, RailType.IRON, 0, 0),
				rail(20, 40, RailType.PLATFORM, 3, 40)
		), 3));
	}
}
