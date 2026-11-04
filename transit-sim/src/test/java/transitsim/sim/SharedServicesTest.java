package transitsim.sim;

import org.junit.jupiter.api.Test;
import transitsim.motion.MotionEvent;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;

/**
 * Two depots running the same route by its timetable, each from a siding of its own: a service either one's trains take
 * isn't taken by the other's too.
 */
class SharedServicesTest {

	private static final long SIDING_1 = 100;
	private static final long SIDING_2 = 200;
	// Services every 10 minutes from 06:00 until 08:00
	private static final int SERVICES = 12;

	@Test
	void sharingDepotsRunEachServiceOnce() {
		final int shared = countDepartures(true);
		final int apart = countDepartures(false);
		assertEquals(SERVICES, shared, "each service once");
		assertTrue(apart > SERVICES, "apart, both depots ran services: " + apart);
	}

	/**
	 * @return how many trains left the platform of the first stop from 06:00 until 08:30
	 */
	private static int countDepartures(boolean share) {
		final TestRoute up = ScenarioLine.makeRoute(1, 0, 1, 2, 3);
		final Map<Long, TestRoute> routes = new HashMap<>();
		routes.put(up.id, up);
		final TickRunner<ScenarioTrack> runner = new TickRunner<>(MONDAY + 5 * HOUR + 50 * MINUTE, routes::get);
		final DepotBoard depot1 = new DepotBoard(Arrays.asList(up));
		final DepotBoard depot2 = new DepotBoard(Arrays.asList(up));
		if (share) {
			depot1.shareServicesWith(depot2);
		}
		runner.addSiding(SIDING_1, depot1, siding(SIDING_1, 0));
		runner.addSiding(SIDING_2, depot2, siding(SIDING_2, 20));
		runner.runUntil(MONDAY + 8 * HOUR + 30 * MINUTE);
		final Set<String> departures = new HashSet<>();
		for (final MotionEvent event : runner.getEvents()) {
			// Rail 2 of either path is the platform of the first stop
			if (event.type == MotionEvent.Type.DEPARTED && event.rail == 2) {
				departures.add(event.trainId + "@" + event.millis);
			}
		}
		return departures.size();
	}

	/**
	 * A siding of its own joining the line at node 1, then up the three stations, turning back at the last one.
	 */
	private static TickRunner.Siding<ScenarioTrack> siding(long sidingId, long sidingNode) {
		final float sidingLength = 3 * 10 + 10;
		final ScenarioTrack terminal = ScenarioTrack.platform(6, 7, 3, 20, true);
		final List<ScenarioTrack> path = Arrays.asList(
				ScenarioTrack.siding(sidingNode, sidingNode + 1, sidingId, sidingLength),
				ScenarioTrack.plain(sidingNode + 1, 2, 60, false),
				ScenarioTrack.platform(2, 3, 1, 20, false),
				ScenarioTrack.plain(3, 4, 400, false),
				ScenarioTrack.platform(4, 5, 2, 20, false),
				ScenarioTrack.plain(5, 6, 400, false),
				terminal,
				terminal.reversed(0),
				ScenarioTrack.plain(6, 15, 400, false),
				ScenarioTrack.plain(15, 14, 20, false),
				ScenarioTrack.plain(14, 13, 400, false),
				ScenarioTrack.plain(13, 12, 20, false),
				ScenarioTrack.plain(12, sidingNode + 1, 60, false),
				ScenarioTrack.siding(sidingNode, sidingNode + 1, sidingId, sidingLength).reversed(0)
		);
		final TickRunner.Siding<ScenarioTrack> untimed = new TickRunner.Siding<>(path, 0, 0, null, "train", 3, 10, sidingLength, 0.01F, 0.02F, 0);
		return new TickRunner.Siding<>(path, 0, 0, ScenarioLine.curveStopTimes(untimed), "train", 3, 10, sidingLength, 0.01F, 0.02F, 0);
	}
}
