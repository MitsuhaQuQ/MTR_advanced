package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.plan.OperatingDay;
import transitsim.plan.RouteTimetable;
import transitsim.planning.DutyGenerator;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where a train goes back after its duty, planned as the runtime sends it (P1-B): to the siding of its pool that needs a
 * train next, else its own; to the depot a service is set to go back to, and with no siding of that depot, the service has
 * no train rather than going back elsewhere.
 */
class PoolReturnPlanTest {

	private static final long HOUR = 60 * 60 * 1000;
	private static final long MINUTE = 60 * 1000;
	private static final long A = 100, B = 200;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void aTrainGoesBackToTheSidingOfItsPoolThatNeedsATrainNext() {
		// Up from A's platform 1 at 06:00, down from B's platform 8 at 07:00, which only B reaches and the up train can't
		final TestRoute up = route(1, 6 * HOUR, 1, 2, 3, 4), down = route(2, 7 * HOUR, 8, 5, 6, 7);
		final DutyGenerator.Result result = DutyGenerator.generate(services(up, down), Arrays.asList(siding(A, 1), siding(B, 8)), Collections.emptyMap(), 5 * MINUTE);
		assertEquals(0, result.uncovered, String.join(", ", result.issues));
		assertEquals(Long.valueOf(B), result.returnSidings.get(A + "/0/0"), "the up train goes back to B: " + result.returnSidings);

		// The runtime's own choice, when the up train reaches its last stop on Monday, is the same
		final long monday = monday();
		final long chosen = DepotDispatcher.chooseReturnSiding(A, Arrays.asList(A, B), sidingId -> result.sidings.getOrDefault(sidingId, Collections.emptyList()),
				(sidingId, slot, day) -> sidingId == A && slot == 0, monday + 6 * HOUR + 7 * MINUTE, sidingId -> true);
		assertEquals(B, chosen);
	}

	@Test
	void aTrainWithNoSidingNeedingOneGoesBackToItsOwn() {
		// The down service leaves before the up train is back, so nothing needs it that day
		final TestRoute up = route(1, 6 * HOUR, 1, 2, 3, 4), down = route(2, 6 * HOUR, 8, 5, 6, 7);
		final DutyGenerator.Result result = DutyGenerator.generate(services(up, down), Arrays.asList(siding(A, 1), siding(B, 8)), Collections.emptyMap(), 5 * MINUTE);
		assertEquals(0, result.uncovered, String.join(", ", result.issues));
		assertTrue(result.returnSidings.isEmpty(), result.returnSidings.toString());
	}

	@Test
	void aTrainWithNoWayBackToItsOwnSidingGoesBackToOneOfItsPoolItCanReach() {
		// Nothing needs the up train that day, so it would go back to A, but path generation found no way back to A from
		// platform 4: it goes back to B
		final TestRoute up = route(1, 6 * HOUR, 1, 2, 3, 4), down = route(2, 6 * HOUR, 8, 5, 6, 7);
		final Map<Long, Map<Long, Long>> backLinks = Collections.singletonMap(A, Collections.singletonMap(4L, transitsim.planning.OperationNetwork.UNREACHABLE));
		final DutyGenerator.Result result = DutyGenerator.generate(services(up, down), Arrays.asList(siding(A, 1), siding(B, 8)), Collections.emptyMap(), 5 * MINUTE, new DutyGenerator.PlanInputs(null, backLinks));
		assertEquals(0, result.uncovered, String.join(", ", result.issues));
		assertEquals(Long.valueOf(B), result.returnSidings.get(A + "/0/0"), result.returnSidings.toString());
		assertTrue(result.issues.stream().noneMatch(issue -> issue.startsWith("NO_WAY_BACK")), result.issues.toString());
	}

	@Test
	void aTrainWithNoWayBackToAnySidingIsTold() {
		final TestRoute up = route(1, 6 * HOUR, 1, 2, 3, 4), down = route(2, 6 * HOUR, 8, 5, 6, 7);
		final Map<Long, Map<Long, Long>> backLinks = new HashMap<>();
		backLinks.put(A, Collections.singletonMap(4L, transitsim.planning.OperationNetwork.UNREACHABLE));
		backLinks.put(B, Collections.singletonMap(4L, transitsim.planning.OperationNetwork.UNREACHABLE));
		final DutyGenerator.Result result = DutyGenerator.generate(services(up, down), Arrays.asList(siding(A, 1), siding(B, 8)), Collections.emptyMap(), 5 * MINUTE, new DutyGenerator.PlanInputs(null, backLinks));
		assertTrue(result.issues.contains("NO_WAY_BACK " + A + " 4"), result.issues.toString());
		assertTrue(result.returnSidings.isEmpty(), "back to its own anyway");
	}

	@Test
	void aServiceSetToGoBackToADepotGoesBackThere() {
		// The down service leaves before the up train is back, so only the order sends the train to B, depot 8's
		final TestRoute up = route(1, 6 * HOUR, 1, 2, 3, 4), down = route(2, 6 * HOUR, 8, 5, 6, 7);
		up.timetable.returnOrders.put((int) (6 * HOUR), new RouteTimetable.ServiceOrder(3, 8));
		final DutyGenerator.SidingInput a = siding(A, 1), b = new DutyGenerator.SidingInput(B, -1, Collections.singletonMap(8L, 0L), 8);
		b.poolKey = "pool";
		final DutyGenerator.Result result = DutyGenerator.generate(services(up, down), Arrays.asList(a, b), Collections.emptyMap(), 5 * MINUTE);
		assertEquals(0, result.uncovered, String.join(", ", result.issues));
		assertEquals(Long.valueOf(B), result.returnSidings.get(A + "/0/0"), result.returnSidings.toString());
	}

	@Test
	void aServiceSetToGoBackToADepotOfAnotherVehicleGoesBackAsUsual() {
		final TestRoute up = route(1, 6 * HOUR, 1, 2, 3, 4), down = route(2, 6 * HOUR, 8, 5, 6, 7);
		up.timetable.returnOrders.put((int) (6 * HOUR), new RouteTimetable.ServiceOrder(3, 8));
		final DutyGenerator.SidingInput b = new DutyGenerator.SidingInput(B, -1, Collections.singletonMap(8L, 0L), 8);
		b.poolKey = "other vehicle";
		final DutyGenerator.Result result = DutyGenerator.generate(services(up, down), Arrays.asList(siding(A, 1), b), Collections.emptyMap(), 5 * MINUTE);
		assertEquals(0, result.uncovered, String.join(", ", result.issues));
		assertTrue(result.returnSidings.isEmpty(), "back to its own, as the runtime sends it: " + result.returnSidings);
	}

	@Test
	void aServiceSetToGoBackToADepotWithNoSidingHereHasNoTrain() {
		final TestRoute up = route(1, 6 * HOUR, 1, 2, 3, 4), down = route(2, 7 * HOUR, 8, 5, 6, 7);
		up.timetable.returnOrders.put((int) (6 * HOUR), new RouteTimetable.ServiceOrder(3, 99));
		final DutyGenerator.Result result = DutyGenerator.generate(services(up, down), Arrays.asList(siding(A, 1), siding(B, 8)), Collections.emptyMap(), 5 * MINUTE);
		assertTrue(result.issues.stream().anyMatch(issue -> issue.startsWith("RETURN_DEPOT 99")), result.issues.toString());
		assertTrue(result.sidings.values().stream().flatMap(List::stream).flatMap(slot -> slot.days.stream()).flatMap(day -> day.segments.stream()).noneMatch(segment -> segment.routeId == 1), "no train runs the up service");
		assertEquals(7, result.uncovered);
	}

	private static DutyGenerator.SidingInput siding(long sidingId, long platformId) {
		final DutyGenerator.SidingInput siding = new DutyGenerator.SidingInput(sidingId, -1, Collections.singletonMap(platformId, 0L), 7);
		siding.poolKey = "pool";
		return siding;
	}

	private static TestRoute route(long id, long millis, long... platformIds) {
		final TestRoute route = new TestRoute(id, platformIds);
		route.timetable.enabled = true;
		route.timetable.services.add((int) millis);
		route.timetable.sanitize();
		return route;
	}

	private static List<DutyGenerator.ServiceInput> services(TestRoute up, TestRoute down) {
		final Map<Long, long[][]> times = new HashMap<>();
		for (final TestRoute route : Arrays.asList(up, down)) {
			final long[][] stopTimes = new long[route.getStops().size()][];
			for (int i = 0; i < stopTimes.length; i++) {
				stopTimes[i] = new long[]{i * 2 * MINUTE, i * 2 * MINUTE + 20000};
			}
			times.put(route.id, stopTimes);
		}
		return DutyGenerator.servicesOf(Arrays.asList(up, down), route -> times.get(route.getRouteId()), 0, route -> fail());
	}

	private static long monday() {
		for (long day = 0; ; day++) {
			if (OperatingDay.getWeekday(day * OperatingDay.MILLIS_PER_DAY) == 0) {
				return day * OperatingDay.MILLIS_PER_DAY;
			}
		}
	}
}
