package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.plan.OperatingDay;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;

/**
 * A day simulated for the editor, with what each service did and what went wrong (S5).
 */
class DayRunTest {

	private static final long DAY_START = MONDAY + 3 * HOUR;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void aDayRunToPlanHasEveryServiceOnTime() {
		final DayRun dayRun = DayRun.run(SimDatasetTest.lines(1, 400, 2, 4, 10), DAY_START, Collections.emptySet(), Collections.emptyList(), () -> false);
		assertEquals(Collections.emptyList(), describe(dayRun.problems));
		// Every ten minutes from 06:00 until 08:00 on each of the two routes
		assertEquals(24, dayRun.trips.size());
		for (final DayRun.Trip trip : dayRun.trips) {
			assertEquals(3, trip.stops.size(), trip.routeId + "@" + ScenarioLine.clock(trip.serviceMillis));
			for (int i = 0; i < trip.stops.size(); i++) {
				final DayRun.Stop stop = trip.stops.get(i);
				assertEquals(i, stop.stopIndex);
				assertTrue(stop.plannedArrivalMillis < 0 || Math.abs(stop.arrivalMillis - stop.plannedArrivalMillis) <= DayRun.REPORT_MILLIS);
			}
		}
	}

	@Test
	void aTrainHeldUpOnTheWayIsLate() {
		final List<HostInput> inputs = Collections.singletonList(new HostInput.ExternalOccupancy(MONDAY + 6 * HOUR + 5 * MINUTE, 1, SimDatasetTest.plain(3, 4, 400).getTrackKey(), MONDAY + 6 * HOUR + 14 * MINUTE));
		final DayRun dayRun = DayRun.run(SimDatasetTest.lines(1, 400, 2, 4, 10), DAY_START, Collections.emptySet(), inputs, () -> false);
		final List<String> problems = describe(dayRun.problems);
		assertTrue(dayRun.problems.stream().anyMatch(problem -> problem.kind == DayRun.ProblemKind.HELD_ON_LINE || problem.kind == DayRun.ProblemKind.WAITING_FOR_PLATFORM), problems.toString());
		assertTrue(dayRun.problems.stream().anyMatch(problem -> problem.kind == DayRun.ProblemKind.LATE && problem.durationMillis > 2 * MINUTE), problems.toString());
	}

	@Test
	void servicesWithoutATrainAreReported() {
		// One train on a long line for a service every three minutes all day, which it can only run now and then
		final DayRun dayRun = DayRun.run(SimDatasetTest.lines(1, 2400, 2, 0, 3), DAY_START, Collections.emptySet(), Collections.emptyList(), () -> false);
		final long notRun = dayRun.problems.stream().filter(problem -> problem.kind == DayRun.ProblemKind.NOT_RUN).count();
		assertTrue(notRun > 100, notRun + " " + dayRun.trips.size());
		assertTrue(dayRun.trips.size() > 10, String.valueOf(dayRun.trips.size()));
	}

	@Test
	void aDraftTimetableIsRunInsteadOfTheWorlds() {
		final SimDataset dataset = SimDatasetTest.lines(1, 400, 2, 4, 10);
		// Every twenty minutes instead of every ten on the first route
		final TestRoute draft = SimDatasetTest.route(1, 0, 10, 1, 2, 3);
		draft.timetable.services.removeIf(service -> service / MINUTE % 20 != 0);
		draft.timetable.sanitize();
		assertTrue(dataset.replaceRoute(new SimDataset.RouteData(draft)));
		final byte[] before = dataset.toBytes();
		final DayRun dayRun = DayRun.run(dataset, DAY_START, Collections.singleton(1L), Collections.emptyList(), () -> false);
		assertEquals(6, dayRun.trips.size());
		assertTrue(dayRun.trips.stream().allMatch(trip -> trip.routeId == 1));
		assertArrayEquals(before, dataset.toBytes());
	}

	@Test
	void aRunCanBeStopped() {
		final DayRun dayRun = DayRun.run(SimDatasetTest.lines(1, 400, 2, 4, 10), DAY_START, Collections.emptySet(), Collections.emptyList(), () -> true);
		assertTrue(dayRun.cancelled);
		assertTrue(dayRun.trips.isEmpty());
	}

	private static List<String> describe(List<DayRun.Problem> problems) {
		return problems.stream().map(problem -> ScenarioLine.clock(problem.time) + " " + problem.kind + " " + problem.routeId + "@" + ScenarioLine.clock(problem.serviceMillis) + " stop " + problem.stopIndex + " " + problem.durationMillis / 1000 + "s " + problem.detail).collect(Collectors.toList());
	}
}
