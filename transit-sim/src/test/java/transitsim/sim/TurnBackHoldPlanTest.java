package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.plan.DepotDuties;
import transitsim.plan.OperatingDay;
import transitsim.planning.DutyGenerator;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A train can wait for its next service on a turn back rail beyond the platform it arrived at, leaving the platform free
 * for others meanwhile (混合折返); without one it can't wait where another train stops in between.
 */
class TurnBackHoldPlanTest {

	private static final long HOUR = 60 * 60 * 1000;
	private static final long MINUTE = 60 * 1000;
	private static final long PLATFORM = 4;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void aTrainWaitsOnTheTurnBackRailWhileAnotherStopsAtItsPlatform() {
		// Up reaches platform 4 at 06:06; another line stops there at 06:12 on its way on; down leaves platform 4 at 06:30
		final TestRoute up = route(1, 6 * HOUR, 1, 2, 3, PLATFORM);
		final TestRoute other = route(2, 6 * HOUR + 6 * MINUTE, 8, 9, 10, PLATFORM, 11);
		final TestRoute down = route(3, 6 * HOUR + 30 * MINUTE, PLATFORM, 5, 6, 7);
		final Map<Long, Long> links = new HashMap<>();
		links.put(1L, 0L);
		links.put(8L, 0L);
		links.put(PLATFORM, 60000L);
		final List<DutyGenerator.SidingInput> sidings = Collections.singletonList(new DutyGenerator.SidingInput(100, -1, links, 7));

		final DutyGenerator.Result without = DutyGenerator.generate(services(up, other, down), sidings, Collections.emptyMap(), HOUR);
		assertFalse(sameDuty(without, 1, 3), "with nowhere to wait, the up train doesn't run the down service");

		// Round the turn back rail and back to the platform takes two minutes
		final Map<Long, Map<Long, Long>> turnTimes = Collections.singletonMap(PLATFORM, Collections.singletonMap(PLATFORM, 2 * MINUTE));
		final DutyGenerator.Result with = DutyGenerator.generate(services(up, other, down), sidings, Collections.emptyMap(), HOUR, new DutyGenerator.PlanInputs(turnTimes, Collections.emptyMap()));
		assertEquals(0, with.uncovered, String.join(", ", with.issues));
		assertTrue(sameDuty(with, 1, 3), "the up train waits on the turn back rail and runs the down service");

		// Where it waits, when it leaves the siding and where it goes back to are kept with the duty (运用补完 step 1)
		final DepotDuties.DayDuty planned = with.sidings.values().stream().flatMap(List::stream).flatMap(slot -> slot.days.stream()).filter(day -> (day.weekdays & 1) != 0 && has(day, 1) && has(day, 3)).findFirst().orElseThrow(AssertionError::new);
		assertEquals(Collections.singletonList(DepotDuties.DayDuty.WAIT_TURN_BACK_RAIL), planned.waits);
		assertEquals(0, planned.outLeadMillis, "out of the siding as long before as its way out to platform 1 takes");
		assertEquals(100, planned.returnSidingId);
		// Without a rail to wait on, the duties that do run wait at the platform where they wait at all
		without.sidings.values().stream().flatMap(List::stream).flatMap(slot -> slot.days.stream()).forEach(day -> {
			assertEquals(Math.max(0, day.segments.size() - 1), day.waits.size());
			assertFalse(day.waits.contains(DepotDuties.DayDuty.WAIT_TURN_BACK_RAIL));
			assertTrue(day.outLeadMillis >= 0);
			assertEquals(100, day.returnSidingId);
		});
	}

	@Test
	void theRailIsHeldAsLongAsEachLegWasMeasured() {
		// Up reaches the platform at 06:06, another line stops there at 06:12, down leaves at 06:30; 21 minutes round the
		// rail. Coming out takes the platform from when the train sets off back to it, so how the round splits matters
		final TestRoute up = route(1, 6 * HOUR, 1, 2, 3, PLATFORM);
		final TestRoute other = route(2, 6 * HOUR + 6 * MINUTE, 8, 9, 10, PLATFORM, 11);
		final TestRoute down = route(3, 6 * HOUR + 30 * MINUTE, PLATFORM, 5, 6, 7);
		final Map<Long, Long> links = new HashMap<>();
		links.put(1L, 0L);
		links.put(8L, 0L);
		links.put(PLATFORM, 60000L);
		final List<DutyGenerator.SidingInput> sidings = Collections.singletonList(new DutyGenerator.SidingInput(100, -1, links, 7));
		final java.util.function.LongFunction<DutyGenerator.Result> plan = inMillis -> {
			final Map<Long, Long> fromPlatform = new HashMap<>();
			fromPlatform.put(PLATFORM, 21 * MINUTE);
			if (inMillis >= 0) {
				fromPlatform.put(DutyGenerator.HOLD_IN_KEY, inMillis);
			}
			return DutyGenerator.generate(services(up, other, down), sidings, Collections.emptyMap(), HOUR, new DutyGenerator.PlanInputs(Collections.singletonMap(PLATFORM, fromPlatform), Collections.emptyMap()));
		};
		// Halves where only the round was measured: back at the platform at about 06:20, after the other line has gone
		assertTrue(sameDuty(plan.apply(-1), 1, 3));
		// 20 minutes in, one out: back at 06:29
		assertTrue(sameDuty(plan.apply(20 * MINUTE), 1, 3));
		// One minute in, 20 out: it would take the platform again from 06:10, while the other line stops there
		assertFalse(sameDuty(plan.apply(MINUTE), 1, 3));
	}

	@Test
	void aTrainCantWaitOnTheRailWithNoTimeToRunInAndOut() {
		final TestRoute up = route(1, 6 * HOUR, 1, 2, 3, PLATFORM);
		final TestRoute other = route(2, 6 * HOUR + 6 * MINUTE, 8, 9, 10, PLATFORM, 11);
		final TestRoute down = route(3, 6 * HOUR + 30 * MINUTE, PLATFORM, 5, 6, 7);
		final Map<Long, Long> links = new HashMap<>();
		links.put(1L, 0L);
		links.put(8L, 0L);
		links.put(PLATFORM, 60000L);
		final List<DutyGenerator.SidingInput> sidings = Collections.singletonList(new DutyGenerator.SidingInput(100, -1, links, 7));
		// Half an hour round the rail: the train would be back too late
		final Map<Long, Map<Long, Long>> turnTimes = Collections.singletonMap(PLATFORM, Collections.singletonMap(PLATFORM, 30 * MINUTE));
		final DutyGenerator.Result result = DutyGenerator.generate(services(up, other, down), sidings, Collections.emptyMap(), HOUR, new DutyGenerator.PlanInputs(turnTimes, Collections.emptyMap()));
		assertFalse(sameDuty(result, 1, 3));
	}

	@Test
	void asManyTrainsWaitAtOnceAsThereArePlacesBeyondThePlatform() {
		// Two trains reach platform 4, at 06:06 and 06:14; another line stops there at 06:20; the two leave it again at
		// 06:30 and 06:40. Both wait beyond the platform at the same time only with two places there (运用补完 step 4:
		// turn back rails and stabling blocks, one train each)
		final TestRoute upA = route(1, 6 * HOUR, 1, 2, 3, PLATFORM);
		final TestRoute upB = route(4, 6 * HOUR + 8 * MINUTE, 12, 13, 14, PLATFORM);
		final TestRoute other = route(2, 6 * HOUR + 14 * MINUTE, 8, 9, 10, PLATFORM, 11);
		final TestRoute downA = route(3, 6 * HOUR + 30 * MINUTE, PLATFORM, 5, 6, 7);
		final TestRoute downB = route(5, 6 * HOUR + 40 * MINUTE, PLATFORM, 15, 16, 17);
		final Map<Long, Long> links = new HashMap<>();
		links.put(1L, 0L);
		links.put(12L, 0L);
		links.put(8L, 0L);
		links.put(PLATFORM, 60000L);
		final List<DutyGenerator.SidingInput> sidings = Collections.singletonList(new DutyGenerator.SidingInput(100, -1, links, 7));
		final List<DutyGenerator.ServiceInput> services = services(upA, upB, other, downA, downB);
		final java.util.function.LongFunction<DutyGenerator.PlanInputs> places = count -> {
			final Map<Long, Long> fromPlatform = new HashMap<>();
			fromPlatform.put(PLATFORM, 2 * MINUTE);
			fromPlatform.put(DutyGenerator.HOLD_IN_KEY, MINUTE);
			if (count > 0) {
				fromPlatform.put(DutyGenerator.HOLD_COUNT_KEY, count);
			}
			return new DutyGenerator.PlanInputs(Collections.singletonMap(PLATFORM, fromPlatform), Collections.emptyMap());
		};
		final java.util.function.LongFunction<DutyGenerator.Result> plan = count -> DutyGenerator.generate(services, sidings, Collections.emptyMap(), HOUR, places.apply(count));
		assertEquals(1, waitingBeyond(plan.apply(0)), "one place where none is given");
		assertEquals(1, waitingBeyond(plan.apply(1)));
		final DutyGenerator.Result two = plan.apply(2);
		assertEquals(2, waitingBeyond(two), String.join(", ", two.issues));
		assertEquals(0, two.uncovered);
		// The check of the duties as planned agrees: two trains at once fit two places, not one
		final java.util.function.LongFunction<List<transitsim.planning.DutyCheck.Break>> check = count -> transitsim.planning.DutyCheck.check(Collections.singletonList(new transitsim.planning.DutyCheck.Group(services, sidings, two.sidings)), Collections.emptyMap(), HOUR, places.apply(count));
		assertEquals(Collections.emptyList(), check.apply(2));
		assertTrue(check.apply(1).stream().anyMatch(broken -> broken.kind == transitsim.planning.DutyCheck.Kind.TURN_BACK_RAIL_TAKEN && broken.platformId == PLATFORM), check.apply(1).toString());
	}

	/**
	 * @return how many of Monday's duties wait beyond the platform between two services
	 */
	private static long waitingBeyond(DutyGenerator.Result result) {
		return result.sidings.values().stream().flatMap(List::stream).flatMap(slot -> slot.days.stream()).filter(day -> (day.weekdays & 1) != 0 && day.waits.contains(DepotDuties.DayDuty.WAIT_TURN_BACK_RAIL)).count();
	}

	@Test
	void aLoopsTrainGoesRoundAgainWithoutChangingEnds() {
		// A loop from platform 1 round to platform 1, a lap of six minutes and its 20 s stand; the next lap leaves 10 s after,
		// less than the least time to change ends (30 s): the train goes round again, changing no ends (2026-11-09)
		final TestRoute lap1 = route(1, 6 * HOUR, 1, 2, 3, 1);
		final TestRoute lap2 = route(2, 6 * HOUR + 6 * MINUTE + 30000, 1, 2, 3, 1);
		final List<DutyGenerator.SidingInput> sidings = Collections.singletonList(new DutyGenerator.SidingInput(100, -1, Collections.singletonMap(1L, 0L), 7));
		final DutyGenerator.Result result = DutyGenerator.generate(services(lap1, lap2), sidings, Collections.emptyMap(), HOUR);
		assertEquals(0, result.uncovered, String.join(", ", result.issues));
		assertTrue(sameDuty(result, 1, 2), "one train runs both laps");
		// A line ending where it doesn't start still changes ends there
		final TestRoute up = route(3, 6 * HOUR, 4, 5, 6, 7);
		final TestRoute down = route(4, 6 * HOUR + 6 * MINUTE + 30000, 7, 8, 9, 4);
		final Map<Long, Long> links = new HashMap<>();
		links.put(4L, 0L);
		links.put(7L, 0L);
		final DutyGenerator.Result line = DutyGenerator.generate(services(up, down), Collections.singletonList(new DutyGenerator.SidingInput(100, -1, links, 7)), Collections.emptyMap(), HOUR);
		assertFalse(sameDuty(line, 3, 4), "20 s isn't time to change ends");
	}

	private static boolean sameDuty(DutyGenerator.Result result, long routeA, long routeB) {
		return result.sidings.values().stream().flatMap(List::stream).flatMap(slot -> slot.days.stream()).anyMatch(day -> (day.weekdays & 1) != 0 && has(day, routeA) && has(day, routeB));
	}

	private static boolean has(DepotDuties.DayDuty day, long routeId) {
		return day.segments.stream().anyMatch(segment -> segment.routeId == routeId);
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
