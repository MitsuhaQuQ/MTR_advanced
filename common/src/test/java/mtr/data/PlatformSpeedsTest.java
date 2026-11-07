package mtr.data;

import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import transitsim.network.SpeedLimits;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A platform's pass speed (optimisation round 3b, step 2): its rail runs by it, at once, under the two speed rule.
 */
class PlatformSpeedsTest {

	@Test
	void aPlatformsRailRunsByItsPassSpeed() {
		final BlockPos start = new BlockPos(0, 64, 0);
		final BlockPos end = new BlockPos(0, 64, 200);
		final Platform platform = new Platform(7, TransportMode.TRAIN, start, end);
		final Rail rail = new Rail(start, RailAngle.S, end, RailAngle.N, RailType.PLATFORM, TransportMode.TRAIN);
		final PathData pathData = new PathData(rail, 7, 40, start, end, 1);
		final Object source = new Object();
		try {
			PlatformSpeeds.update(source, Collections.singleton(platform));
			// The default: the platform rail type's 80, run up to 85
			assertEquals(SpeedLimits.toBlocksPerTick(80), pathData.getMaxSpeed(), 1E-6);
			assertEquals(SpeedLimits.toBlocksPerTick(85), pathData.getRunSpeed(), 1E-6);

			// Set to 160: at once, run up to 165, planned at 160
			platform.passSpeed = 160;
			PlatformSpeeds.update(source, Collections.singleton(platform));
			assertEquals(160, platform.getPassSpeedKmh());
			assertEquals(SpeedLimits.toBlocksPerTick(165), pathData.getRunSpeed(), 1E-6);
			assertEquals(SpeedLimits.toBlocksPerTick(160), pathData.getPlanSpeed(), 1E-6);
			// A route's speed limit still caps it
			PathData.capSpeed(Collections.singletonList(pathData), 100);
			assertEquals(SpeedLimits.toBlocksPerTick(105), pathData.getRunSpeed(), 1E-6);

			// Taken away, the default again
			PlatformSpeeds.update(source, Collections.emptySet());
			PathData.capSpeed(Collections.singletonList(pathData), 0);
			assertEquals(SpeedLimits.toBlocksPerTick(85), pathData.getRunSpeed(), 1E-6);
		} finally {
			PlatformSpeeds.update(source, Collections.emptySet());
		}
	}

	@Test
	void sidingsAndTurnBackRailsHaveTheirOwnLabels() {
		assertEquals(25, SpeedRules.labelKmh(RailType.SIDING, TransportMode.TRAIN));
		assertEquals(65, SpeedRules.labelKmh(RailType.TURN_BACK, TransportMode.TRAIN));
		// Cable cars keep the types' labels
		assertEquals(RailType.SIDING.speedLimit, SpeedRules.labelKmh(RailType.SIDING, TransportMode.CABLE_CAR));
	}
}
