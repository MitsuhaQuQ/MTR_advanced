package mtr.advanced;

import mtr.data.Rail;
import mtr.data.RailAngle;
import mtr.data.RailType;
import mtr.data.TransportMode;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import transitsim.motion.SpeedProfile;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpeedCurvesTest {

	private static final double ACCELERATION = 0.01;
	private static final double DECELERATION = 0.02;
	private static final double TOLERANCE = 1E-6;

	private static PathData rail(int from, int to, RailType railType, int dwellTime) {
		final BlockPos start = new BlockPos(0, 64, from);
		final BlockPos end = new BlockPos(0, 64, to);
		return new PathData(new Rail(start, RailAngle.fromAngle(90), end, RailAngle.fromAngle(270), railType, TransportMode.TRAIN), 0, dwellTime, start, end, 0);
	}

	@Test
	void keepsTheLimitUntilTheTailHasLeft() {
		final List<PathData> path = new ArrayList<>();
		path.add(rail(0, 20, RailType.SIDING, 0));
		path.add(rail(20, 120, RailType.IRON, 0));
		path.add(rail(120, 160, RailType.WOODEN, 0));
		path.add(rail(160, 260, RailType.IRON, 0));
		path.add(rail(260, 300, RailType.PLATFORM, 40));
		final SpeedCurves curves = SpeedCurves.of(path, TransportMode.TRAIN, 15, 30, ACCELERATION, DECELERATION);
		assertEquals(1, curves.runs.size());
		final SpeedProfile profile = curves.runs.get(0).fastest;
		final double wooden = RailType.WOODEN.maxBlocksPerTick;
		assertTrue(profile.getSpeed(120) <= wooden + TOLERANCE);
		// The tail leaves the slow rail once the head is 30 blocks past it
		assertEquals(wooden, profile.getSpeed(189), TOLERANCE);
		assertTrue(profile.getSpeed(195) > wooden);
		assertEquals(0, profile.getSpeed(300), TOLERANCE);
	}

	@Test
	void startsAgainATrainLengthOnAfterReversing() {
		final List<PathData> path = new ArrayList<>();
		path.add(rail(0, 20, RailType.SIDING, 0));
		path.add(rail(20, 120, RailType.IRON, 0));
		final PathData platform = rail(120, 160, RailType.PLATFORM, 40);
		path.add(platform);
		path.add(new PathData(platform.rail, 0, 0, platform.getLastPos(), platform.startingPos, 0));
		path.add(rail(160, 260, RailType.IRON, 0));
		final SpeedCurves curves = SpeedCurves.of(path, TransportMode.TRAIN, 15, 30, ACCELERATION, DECELERATION);
		assertEquals(2, curves.runs.size());
		assertEquals(160, curves.runs.get(0).fastest.endPosition, TOLERANCE);
		assertEquals(190, curves.runs.get(1).fastest.startPosition, TOLERANCE);
		assertSame(curves.runs.get(1), curves.getRun(160));
	}
}
