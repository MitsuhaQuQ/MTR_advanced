package transitsim.run;

import org.junit.jupiter.api.Test;
import transitsim.motion.SpeedCurves;
import transitsim.plan.PathTimes;
import transitsim.plan.StopTimes;
import transitsim.sim.RunTrack;
import transitsim.sim.SimDataset;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A way out of a siding that comes onto the first platform against the route, stops, turns round and stops there again
 * the way the route runs (2026-11-16, the user's world, a loop line whose depot track joins at the loop's first platform
 * from the far side). The service starts from the second stop: the train leaves the siding in time for the first stop and
 * the turn, and the first stop has no time of its own on the timetable.
 */
class TurnedAtFirstPlatformTest {

	private static final double LENGTH = 75;
	private static final double DECELERATION = 4.0 / 400;
	private static final int DWELL = 40;

	private static SimDataset.DataTrack platform(long from, long to, long stopId) {
		return new SimDataset.DataTrack(from, to, 80, true, false, stopId, DWELL, 0, 1, false, true, false);
	}

	private static SimDataset.DataTrack line(long from, long to, double length) {
		return new SimDataset.DataTrack(from, to, length, false, false, 0, 0, 0, 1.11F, true, true, false);
	}

	private static StopTimes times(List<SimDataset.DataTrack> path) {
		final StopTimes stopTimes = new StopTimes();
		final SpeedCurves curves = SpeedCurves.of(RunTrack.toTrackView(path, 0, 0, LENGTH), LENGTH, LENGTH, DECELERATION, DECELERATION);
		PathTimes.curveTimes(path, curves, new ArrayList<>(), new HashMap<>(), stopTimes);
		return stopTimes;
	}

	@Test
	void aPlatformTurnedAtOnTheWayOutIsNoFirstStop() {
		final List<SimDataset.DataTrack> path = Arrays.asList(line(1, 2, 200), platform(2, 3, 10), platform(3, 2, 10), line(2, 5, 300), platform(5, 6, 11));
		assertTrue(PathTimes.isTurnedAt(path, 1));
		assertFalse(PathTimes.isTurnedAt(path, 2));
		final StopTimes turned = times(path);
		assertEquals(2, turned.getFirstIndex());

		// Against the same path with no turn: reaching the platform the first time, its stop and the turn come before
		final List<SimDataset.DataTrack> straight = Arrays.asList(line(1, 2, 200), platform(2, 3, 10), line(3, 5, 300), platform(5, 6, 11));
		assertFalse(PathTimes.isTurnedAt(straight, 1));
		final StopTimes plain = times(straight);
		assertEquals(1, plain.getFirstIndex());
		assertTrue(turned.getLeadMillis() >= plain.getLeadMillis() + DWELL * 10 * 50L, turned.getLeadMillis() + " against " + plain.getLeadMillis());
	}
}
