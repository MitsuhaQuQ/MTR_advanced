package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.plan.DepotDuties;
import transitsim.plan.OperatingDay;
import transitsim.planning.DutyCheck;
import transitsim.planning.DutyGenerator;
import transitsim.planning.OperationNetwork;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Duties already planned are kept while the planner's rules still hold with new times, and planned again once one breaks,
 * by however little (optimisation round 4, step 4).
 */
class DutyCheckTest {

	private static final long MINUTE = 60000;
	private static final long HOUR = 60 * MINUTE;
	private static final long LAYOVER = 30 * MINUTE;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	/**
	 * Up from 100 to 102 and down from 102 to 100 every ten minutes, the down service two minutes after the up one leaves:
	 * a train arriving at 102 has two minutes less the run to turn round.
	 */
	private static List<DutyGenerator.ServiceInput> shortLine(long runSeconds) {
		return shortLine(runSeconds, 0, 24 * 60);
	}

	/**
	 * Only between two times of day, so the train comes out of its siding for them and goes back after.
	 */
	private static List<DutyGenerator.ServiceInput> shortLine(long runSeconds, int fromMinute, int toMinute) {
		final List<DutyGenerator.ServiceInput> services = new ArrayList<>();
		final long[][] plans = {{0, 0}, {runSeconds * 600, runSeconds * 600 + 10000}, {runSeconds * 1000, runSeconds * 1000}};
		for (int minute = fromMinute; minute < toMinute; minute += 10) {
			services.add(new DutyGenerator.ServiceInput(1, (int) (minute * MINUTE), DepotDuties.ALL_WEEKDAYS, minute * MINUTE, new long[]{100, 101, 102}, plans, new boolean[3], 0, 0, -1, 0));
			services.add(new DutyGenerator.ServiceInput(2, (int) ((minute + 2) * MINUTE), DepotDuties.ALL_WEEKDAYS, (minute + 2) * MINUTE, new long[]{102, 103, 100}, plans, new boolean[3], 0, 0, -1, 0));
		}
		return services;
	}

	private static Map<Long, Long> stations() {
		final Map<Long, Long> stationOf = new HashMap<>();
		stationOf.put(100L, 1L);
		stationOf.put(101L, 2L);
		stationOf.put(103L, 2L);
		stationOf.put(102L, 3L);
		return stationOf;
	}

	private static List<DutyGenerator.SidingInput> siding() {
		return Collections.singletonList(new DutyGenerator.SidingInput(100, 40));
	}

	private static List<DutyCheck.Break> check(List<DutyGenerator.ServiceInput> services, List<DutyGenerator.SidingInput> sidings, DutyGenerator.Result planned, Map<Long, Long> stationOf, long maxLayover, DutyGenerator.PlanInputs inputs) {
		return DutyCheck.check(Collections.singletonList(new DutyCheck.Group(services, sidings, planned.sidings)), stationOf, maxLayover, inputs);
	}

	@Test
	void dutiesHoldWithTheTimesTheyWerePlannedBy() {
		final DutyGenerator.Result planned = DutyGenerator.generate(shortLine(84), siding(), stations(), LAYOVER);
		assertEquals(1, planned.trains);
		assertEquals(Collections.emptyList(), check(shortLine(84), siding(), planned, stations(), LAYOVER, DutyGenerator.PlanInputs.NONE));
	}

	@Test
	void aSecondTooLongBreaksATightTurn() {
		// Planned with 36 s to turn round at 102 against the 30 needed
		final DutyGenerator.Result planned = DutyGenerator.generate(shortLine(84), siding(), stations(), LAYOVER);
		// Six seconds longer leaves exactly 30: still holds
		assertEquals(Collections.emptyList(), check(shortLine(90), siding(), planned, stations(), LAYOVER, DutyGenerator.PlanInputs.NONE));
		// One more leaves 29: broken, at 102, every time the train turns round there
		final List<DutyCheck.Break> breaks = check(shortLine(91), siding(), planned, stations(), LAYOVER, DutyGenerator.PlanInputs.NONE);
		assertFalse(breaks.isEmpty());
		breaks.forEach(broken -> {
			assertEquals(DutyCheck.Kind.SHORT_TURNAROUND, broken.kind);
			assertEquals(102, broken.platformId);
			assertEquals(29000, broken.givenMillis);
			assertEquals(DutyGenerator.MIN_TURNAROUND_MILLIS, broken.neededMillis);
		});
	}

	@Test
	void aMinuteLongerWithRoomToSpareHolds() {
		// Planned with a 30 s run and 90 s to turn round: a minute longer still leaves the 30 needed
		final DutyGenerator.Result planned = DutyGenerator.generate(shortLine(30), siding(), stations(), LAYOVER);
		assertEquals(Collections.emptyList(), check(shortLine(90), siding(), planned, stations(), LAYOVER, DutyGenerator.PlanInputs.NONE));
	}

	@Test
	void aLongerTimeToChangeEndsIsChecked() {
		final DutyGenerator.Result planned = DutyGenerator.generate(shortLine(84), siding(), stations(), LAYOVER);
		assertEquals(Collections.emptyList(), check(shortLine(84), siding(), planned, stations(), LAYOVER, new DutyGenerator.PlanInputs(null, null, null, null, 36000)));
		assertFalse(check(shortLine(84), siding(), planned, stations(), LAYOVER, new DutyGenerator.PlanInputs(null, null, null, null, 37000)).isEmpty());
	}

	@Test
	void noWayBackIsABreak() {
		final List<DutyGenerator.ServiceInput> morning = shortLine(84, 6 * 60, 8 * 60);
		final DutyGenerator.Result planned = DutyGenerator.generate(morning, siding(), stations(), LAYOVER);
		assertEquals(Collections.emptyList(), check(morning, siding(), planned, stations(), LAYOVER, DutyGenerator.PlanInputs.NONE));
		final DutyGenerator.PlanInputs noWayBack = new DutyGenerator.PlanInputs(null, Collections.singletonMap(100L, Collections.singletonMap(100L, OperationNetwork.UNREACHABLE)));
		final List<DutyCheck.Break> breaks = check(morning, siding(), planned, stations(), LAYOVER, noWayBack);
		assertFalse(breaks.isEmpty());
		assertTrue(breaks.stream().allMatch(broken -> broken.kind == DutyCheck.Kind.NO_WAY_BACK && broken.platformId == 100), breaks.toString());
	}

	@Test
	void noWayOutIsABreak() {
		final List<DutyGenerator.SidingInput> out = Collections.singletonList(new DutyGenerator.SidingInput(100, 40, Collections.singletonMap(100L, MINUTE)));
		final List<DutyGenerator.ServiceInput> morning = shortLine(84, 6 * 60, 8 * 60);
		final DutyGenerator.Result planned = DutyGenerator.generate(morning, out, stations(), LAYOVER);
		assertEquals(Collections.emptyList(), check(morning, out, planned, stations(), LAYOVER, DutyGenerator.PlanInputs.NONE));
		final List<DutyGenerator.SidingInput> elsewhere = Collections.singletonList(new DutyGenerator.SidingInput(100, 40, Collections.singletonMap(102L, MINUTE)));
		assertTrue(check(morning, elsewhere, planned, stations(), LAYOVER, DutyGenerator.PlanInputs.NONE).stream().anyMatch(broken -> broken.kind == DutyCheck.Kind.NO_WAY_OUT));
	}

	@Test
	void aServiceNoLongerThereIsABreak() {
		final DutyGenerator.Result planned = DutyGenerator.generate(shortLine(84), siding(), stations(), LAYOVER);
		final List<DutyGenerator.ServiceInput> fewer = shortLine(84);
		fewer.remove(fewer.size() - 1);
		assertTrue(check(fewer, siding(), planned, stations(), LAYOVER, DutyGenerator.PlanInputs.NONE).stream().anyMatch(broken -> broken.kind == DutyCheck.Kind.NO_SERVICE));
	}

	@Test
	void aTrainWaitingOnTheTurnBackRailHoldsWhileItsLegsFit() {
		// As TurnBackHoldPlanTest: up reaches platform 4 at 06:06, another line stops there at 06:12, down leaves at 06:30;
		// 21 minutes round the turn back rail, 20 of them in
		final TestRoute up = route(1, 6 * HOUR, 1, 2, 3, 4);
		final TestRoute other = route(2, 6 * HOUR + 6 * MINUTE, 8, 9, 10, 4, 11);
		final TestRoute down = route(3, 6 * HOUR + 30 * MINUTE, 4, 5, 6, 7);
		final Map<Long, Long> links = new HashMap<>();
		links.put(1L, 0L);
		links.put(8L, 0L);
		links.put(4L, 60000L);
		final List<DutyGenerator.SidingInput> sidings = Collections.singletonList(new DutyGenerator.SidingInput(100, -1, links, 7));
		final List<DutyGenerator.ServiceInput> services = services(up, other, down);
		final DutyGenerator.Result planned = DutyGenerator.generate(services, sidings, Collections.emptyMap(), HOUR, hold(20 * MINUTE));
		assertEquals(Collections.emptyList(), check(services, sidings, planned, Collections.emptyMap(), HOUR, hold(20 * MINUTE)));
		// One minute in, 20 out: it would take the platform again while the other line stops there
		final List<DutyCheck.Break> breaks = check(services, sidings, planned, Collections.emptyMap(), HOUR, hold(MINUTE));
		assertFalse(breaks.isEmpty());
		assertTrue(breaks.stream().allMatch(broken -> broken.kind == DutyCheck.Kind.PLATFORM_TAKEN && broken.platformId == 4), breaks.toString());
	}

	private static DutyGenerator.PlanInputs hold(long inMillis) {
		final Map<Long, Long> fromPlatform = new HashMap<>();
		fromPlatform.put(4L, 21 * MINUTE);
		fromPlatform.put(DutyGenerator.HOLD_IN_KEY, inMillis);
		return new DutyGenerator.PlanInputs(Collections.singletonMap(4L, fromPlatform), Collections.emptyMap());
	}

	private static TestRoute route(long id, long millis, long... platformIds) {
		final TestRoute route = new TestRoute(id, platformIds);
		route.timetable.enabled = true;
		route.timetable.services.add((int) millis);
		route.timetable.sanitize();
		return route;
	}

	private static List<DutyGenerator.ServiceInput> services(TestRoute... routes) {
		final Map<Long, long[][]> times = new HashMap<>();
		for (final TestRoute route : routes) {
			final long[][] stopTimes = new long[route.getStops().size()][];
			for (int i = 0; i < stopTimes.length; i++) {
				stopTimes[i] = new long[]{i * 2 * MINUTE, i * 2 * MINUTE + 20000};
			}
			times.put(route.id, stopTimes);
		}
		return DutyGenerator.servicesOf(Arrays.asList(routes), route -> times.get(route.getRouteId()), 0, route -> fail());
	}
}
