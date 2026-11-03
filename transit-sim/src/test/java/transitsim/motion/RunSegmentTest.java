package transitsim.motion;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Trains on speed curves running along run segments on the server (S2c): position from the time since setting off,
 * a new segment from where the train is when the place to stop changes, and harder braking when it can't stop in time.
 */
class RunSegmentTest {

	private static final int TICKS = 4000;
	private static final double TOLERANCE = 1E-6;

	@Test
	void positionDoesNotDependOnTheSteps() {
		final SpeedCurves.Run run = curves().runs.get(0);
		final RunSegment once = RunSegment.start(null, run, run.fastest.startPosition, 0.01, Double.POSITIVE_INFINITY, run.fastest.endPosition);
		final RunSegment small = RunSegment.start(null, run, run.fastest.startPosition, 0.01, Double.POSITIVE_INFINITY, run.fastest.endPosition);
		final RunSegment mixed = RunSegment.start(null, run, run.fastest.startPosition, 0.01, Double.POSITIVE_INFINITY, run.fastest.endPosition);
		once.advance(330);
		for (int i = 0; i < 1320; i++) {
			small.advance(0.25);
		}
		for (final double step : new double[]{1, 7, 33, 0.5, 288.5}) {
			mixed.advance(step);
		}
		assertEquals(once.getPosition(), small.getPosition(), TOLERANCE);
		assertEquals(once.getPosition(), mixed.getPosition(), TOLERANCE);
		assertEquals(once.getSpeed(), mixed.getSpeed(), TOLERANCE);
	}

	@Test
	void aNewSegmentCarriesOnFromWhereTheTrainIs() {
		final SpeedCurves.Run run = curves().runs.get(0);
		final RunSegment first = RunSegment.start(null, run, run.fastest.startPosition, 0.01, Double.POSITIVE_INFINITY, run.fastest.endPosition);
		first.advance(200);
		final double position = first.getPosition();
		final double speed = first.getSpeed();
		// The line ahead is taken: stop 100 blocks on
		final RunSegment second = RunSegment.start(first, run, position, speed, Double.POSITIVE_INFINITY, position + 100);
		assertEquals(first.version + 1, second.version);
		assertEquals(position, second.getPosition(), TOLERANCE);
		assertEquals(speed, second.getSpeed(), TOLERANCE);
		assertFalse(second.emergency);
		second.advance(10000);
		assertEquals(position + 100, second.getPosition(), TOLERANCE);
		assertTrue(second.isFinished());
		assertTrue(second.matches(run, Double.POSITIVE_INFINITY, position + 100, position + 100));
		assertFalse(second.matches(run, 0.3, position + 100, position + 100));
	}

	@Test
	void brakesHarderWhenItCantStopInTime() {
		final SpeedCurves.Run run = curves().runs.get(0);
		final double position = run.fastest.startPosition + 200;
		// At 0.5 blocks per tick it needs 6.25 blocks to stop at 0.02, so 4 blocks is too short
		final RunSegment segment = RunSegment.start(null, run, position, 0.5, Double.POSITIVE_INFINITY, position + 4);
		assertTrue(segment.emergency);
		double last = segment.getPosition();
		while (!segment.isFinished()) {
			segment.advance(1);
			assertTrue(segment.getPosition() >= last - TOLERANCE);
			assertTrue(segment.getPosition() <= position + 4 + TOLERANCE, "never past where it has to stop");
			last = segment.getPosition();
		}
		assertEquals(position + 4, segment.getPosition(), TOLERANCE);
	}

	@Test
	void arrivesWhenTheCurveSays() {
		final List<Consumer<MotionFixture>> setUps = new ArrayList<>();
		setUps.add(fixture -> {
		});
		setUps.add(fixture -> fixture.cruiseCap = 0.15F);
		for (final Consumer<MotionFixture> setUp : setUps) {
			final MotionFixture fixture = line();
			setUp.accept(fixture);
			final MotionFixture old = line();
			setUp.accept(old);
			int departure = -1;
			double expected = 0;
			int arrivals = 0;
			int oldArrival = -1;
			// Long enough for two stations even cruising slowly
			for (int i = 0; i < TICKS * 2; i++) {
				final int startUps = fixture.startUps;
				final int stops = fixture.stopsReached;
				final int oldStops = old.stopsReached;
				fixture.step(false, 1);
				old.step(true, 1);
				if (old.stopsReached > oldStops) {
					oldArrival = old.tick;
				}
				if (fixture.startUps > startUps) {
					// It moves off a tick after starting up, from standing at the start of the run
					departure = fixture.tick;
					expected = fixture.getProfile(fixture.railProgress).getDuration();
				}
				if (fixture.stopsReached > stops) {
					// The stop counts a tick after the train is there
					assertEquals(departure + expected + 1, fixture.tick, 1.5, "arrival " + arrivals);
					arrivals++;
				}
				if (old.stopsReached > oldStops && arrivals > 0) {
					assertTrue(oldArrival >= fixture.tick - 1, "the old rules weren't earlier");
				}
			}
			assertTrue(arrivals >= 2);
		}
	}

	@Test
	void stopsShortOfATakenRail() {
		// The second platform is taken until well after the train has left the first station
		final MotionFixture fixture = line();
		fixture.blockedAt = (tick, rail) -> rail == 4 && tick < 2000;
		int versions = 0;
		boolean stoppedOnTheWay = false;
		for (int i = 0; i < TICKS; i++) {
			fixture.step(false, 1);
			if (fixture.tick < 2000) {
				assertTrue(fixture.railProgress <= fixture.getDistance(3) + TOLERANCE, "ran onto the taken rail at tick " + fixture.tick);
				stoppedOnTheWay |= fixture.speed == 0 && Math.abs(fixture.railProgress - fixture.getDistance(3)) < TOLERANCE;
			}
			if (fixture.runSegment != null) {
				versions = Math.max(versions, fixture.runSegment.version);
			}
		}
		assertTrue(stoppedOnTheWay);
		assertTrue(fixture.stopsReached >= 2, "carried on once the rail was free");
		assertTrue(versions > 2);
	}

	@Test
	void emergencyWhenARailRightAheadIsTaken() {
		final MotionFixture probe = line();
		int takenFrom = -1;
		for (int i = 0; i < TICKS && takenFrom < 0; i++) {
			probe.step(false, 1);
			// Too close to stop with its own braking: v² > 2 × 0.02 × 1.5
			if (probe.speed > 0.25 && probe.getDistance(1) - probe.railProgress < 1.5 && probe.getDistance(1) > probe.railProgress) {
				takenFrom = probe.tick;
			}
		}
		assertTrue(takenFrom > 0);
		final int from = takenFrom;
		final MotionFixture fixture = line();
		fixture.blockedAt = (tick, rail) -> rail == 2 && tick >= from;
		boolean emergency = false;
		for (int i = 0; i < TICKS; i++) {
			fixture.step(false, 1);
			emergency |= fixture.runSegment != null && fixture.runSegment.emergency;
			assertTrue(fixture.railProgress <= fixture.getDistance(1) + TOLERANCE || fixture.tick < from, "ran onto the taken rail");
		}
		assertTrue(emergency);
		assertEquals(fixture.getDistance(1), fixture.railProgress, TOLERANCE);
		assertEquals(0, fixture.speed);
	}

	@Test
	void switchingRulesWhileRunningDoesNotMoveTheTrain() {
		final MotionFixture fixture = line();
		double last = fixture.railProgress;
		boolean wasOnRoute = false;
		for (int i = 0; i < TICKS; i++) {
			// New rules, then the old ones for a while, then back
			fixture.step(i >= 400 && i < 700, 1);
			// Only while it runs: the host puts it at the start of the siding when it leaves it
			assertTrue(!fixture.onRoute || !wasOnRoute || fixture.railProgress - last <= 0.7 + TOLERANCE && fixture.railProgress - last >= -TOLERANCE, "jumped at tick " + fixture.tick);
			last = fixture.railProgress;
			wasOnRoute = fixture.onRoute;
		}
		assertTrue(fixture.stopsReached >= 2);
	}

	/**
	 * @return the ticks the train reaches each stop
	 */
	private static List<Integer> arrivals(boolean legacy, Consumer<MotionFixture> setUp) {
		final MotionFixture fixture = line();
		setUp.accept(fixture);
		final List<Integer> arrivals = new ArrayList<>();
		for (int i = 0; i < TICKS; i++) {
			final int stops = fixture.stopsReached;
			fixture.step(legacy, 1);
			if (fixture.stopsReached > stops) {
				arrivals.add(fixture.tick);
			}
		}
		return arrivals;
	}

	private static MotionFixture line() {
		final MotionFixture fixture = new MotionFixture()
				.rail(30, 0, 0.3F)
				.rail(300, 0, 0.6F)
				.rail(60, 2, 0.3F, false, false)
				.rail(400, 0, 0.6F)
				.rail(60, 2, 0.3F, false, false)
				.rail(200, 0, 0.4F);
		fixture.speedCurve = true;
		return fixture;
	}

	private static SpeedCurves curves() {
		return line().getCurves();
	}
}
