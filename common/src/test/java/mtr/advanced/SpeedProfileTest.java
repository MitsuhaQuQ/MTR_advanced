package mtr.advanced;

import mtr.data.Rail;
import mtr.data.RailAngle;
import mtr.data.RailType;
import mtr.data.TransportMode;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpeedProfileTest {

	private static final double ACCELERATION = 0.01;
	private static final double DECELERATION = 0.02;
	private static final double TOLERANCE = 1E-6;

	@Test
	void speedsUpCruisesAndBrakes() {
		final SpeedProfile profile = SpeedProfile.build(new double[]{0, 200}, new double[]{1}, ACCELERATION, DECELERATION, Double.POSITIVE_INFINITY);
		// 50 blocks and 100 ticks to reach 1, 25 blocks and 50 ticks to stop, 125 blocks at 1 in between
		assertEquals(275, profile.getDuration(), TOLERANCE);
		assertEquals(1, profile.getSpeed(100), TOLERANCE);
		assertEquals(0, profile.getSpeed(200), TOLERANCE);
		assertEquals(100, profile.getTime(50), TOLERANCE);
		assertEquals(50, profile.getPosition(100), TOLERANCE);
	}

	@Test
	void shortRunsNeverReachTheLimit() {
		final SpeedProfile profile = SpeedProfile.build(new double[]{0, 30}, new double[]{1}, ACCELERATION, ACCELERATION, Double.POSITIVE_INFINITY);
		assertEquals(2 * Math.sqrt(2 * 15 / ACCELERATION), profile.getDuration(), TOLERANCE);
		assertEquals(Math.sqrt(2 * ACCELERATION * 15), profile.getSpeed(15), TOLERANCE);
	}

	@Test
	void brakesBeforeALowerLimitAhead() {
		final SpeedProfile profile = SpeedProfile.build(new double[]{0, 300, 400, 700}, new double[]{1, 0.3, 1}, ACCELERATION, DECELERATION, Double.POSITIVE_INFINITY);
		assertTrue(profile.getSpeed(300) <= 0.3 + TOLERANCE);
		assertTrue(profile.getSpeed(290) < 1);
		assertEquals(0.3, profile.getSpeed(350), TOLERANCE);
		// Only speeds up again past the slow part
		assertEquals(0.3, profile.getSpeed(400), TOLERANCE);
		assertTrue(profile.getSpeed(410) > 0.3);
	}

	@Test
	void cruisesSlowerToArriveOnTime() {
		final double[] breaks = {0, 1000};
		final double[] limits = {1};
		final double fastest = SpeedProfile.build(breaks, limits, ACCELERATION, DECELERATION, Double.POSITIVE_INFINITY).getDuration();
		final double cap = SpeedProfile.findCap(breaks, limits, ACCELERATION, DECELERATION, fastest + 200, 0.1);
		assertTrue(cap < 1);
		assertEquals(fastest + 200, SpeedProfile.build(breaks, limits, ACCELERATION, DECELERATION, cap).getDuration(), 1);
		// A late train runs as fast as it can
		assertTrue(Double.isInfinite(SpeedProfile.findCap(breaks, limits, ACCELERATION, DECELERATION, fastest - 10, 0.1)));
		// It can't crawl, so a lot of spare time is spent waiting at the stop instead
		assertEquals(0.1, SpeedProfile.findCap(breaks, limits, ACCELERATION, DECELERATION, fastest * 100, 0.1), TOLERANCE);
	}

	@Test
	void movingTickByTickArrivesWhenWorkedOut() {
		final SpeedProfile profile = SpeedProfile.build(new double[]{0, 300, 400, 700}, new double[]{1, 0.3, 1}, ACCELERATION, DECELERATION, Double.POSITIVE_INFINITY);
		double position = profile.startPosition;
		int ticks = 0;
		while (position < profile.endPosition - TOLERANCE) {
			position = profile.advance(position, 1);
			ticks++;
		}
		assertEquals(Math.ceil(profile.getDuration() - TOLERANCE), ticks);
	}

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
