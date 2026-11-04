package transitsim.sim;

import org.junit.jupiter.api.Test;
import transitsim.plan.DepotDuties;
import transitsim.plan.PlanRoute;
import transitsim.planning.AutoDuties;
import transitsim.planning.DutyGenerator;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static transitsim.sim.ScenarioLine.MINUTE;

/**
 * The duties a depot running by a timetable gets when it has none set (decoupling document, step D).
 */
class AutoDutiesTest {

	// Up from platform 1 to 3, down from 3 to 4; platforms 1 and 4 are one station. Services every 10 minutes 06:00 to 08:00
	private final TestRoute up = ScenarioLine.makeRoute(1, 0, 1, 2, 3);
	private final TestRoute down = ScenarioLine.makeRoute(2, 5, 3, 5, 4);

	private AutoDuties generate(int maxTrains) {
		final Map<Long, Long> stationOf = new HashMap<>();
		stationOf.put(1L, 10L);
		stationOf.put(4L, 10L);
		stationOf.put(2L, 20L);
		stationOf.put(5L, 20L);
		stationOf.put(3L, 30L);
		// Each run between two stops takes 2 minutes, with 20 seconds at each
		final long[][] times = {{0, 20000}, {2 * MINUTE, 2 * MINUTE + 20000}, {4 * MINUTE, 4 * MINUTE}};
		final List<PlanRoute> routes = Arrays.asList(up, down);
		return AutoDuties.generate(routes, route -> times, 0, Collections.singletonList(new DutyGenerator.SidingInput(100, maxTrains)), stationOf, false);
	}

	@Test
	void everyServiceIsInOneDuty() {
		final AutoDuties autoDuties = generate(-1);
		assertTrue(autoDuties.duties.diagramMode);
		assertEquals(0, autoDuties.uncovered);
		final Set<String> runs = new HashSet<>();
		autoDuties.duties.sidings.get(100L).forEach(slot -> slot.days.forEach(day -> day.segments.forEach(segment -> assertTrue(runs.add(segment.routeId + "@" + segment.service), "twice: " + segment.routeId + "@" + segment.service))));
		// 12 services each way
		assertEquals(24, runs.size());
		// A round trip with turning back is well under 10 minutes, so few trains are needed
		assertTrue(autoDuties.trains <= 2, "trains: " + autoDuties.trains);
	}

	@Test
	void servicesBeyondTheSidingsTrainsAreLeftOver() {
		final AutoDuties autoDuties = generate(0);
		assertEquals(0, autoDuties.duties.sidings.getOrDefault(100L, Collections.emptyList()).size());
		// Counted once for each day of the week a service runs
		assertEquals(24 * 7, autoDuties.uncovered);
	}

	@Test
	void theSameTimetablesGiveTheSameDuties() {
		assertArrayEquals(generate(-1).duties.toBytes(), generate(-1).duties.toBytes());
		assertArrayEquals(generate(1).duties.toBytes(), generate(1).duties.toBytes());
		final DepotDuties duties = generate(-1).duties;
		assertTrue(duties.sidings.get(100L).size() > 0);
	}
}
