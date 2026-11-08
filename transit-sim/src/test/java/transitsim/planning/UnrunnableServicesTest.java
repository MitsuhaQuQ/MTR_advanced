package transitsim.planning;

import org.junit.jupiter.api.Test;
import transitsim.plan.DepotDuties;
import transitsim.plan.RouteTimetable;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Services no number of trains could run aren't trains short (2026-11-09): on a player's line, a service no train could
 * get to had the planner spread the services for too few trains, leaving two thirds of them without a train though the
 * siding held 256. Down from platform A, where up ends and no siding reaches, to B1; up from B2, the only platform the
 * siding gets out to, to A; turning from B1 to B2 measured; a service every 10 minutes each way.
 */
class UnrunnableServicesTest {

	private static final long MINUTE = 60000;
	private static final long A = 1, D2 = 2, D3 = 3, B1 = 4, B2 = 5, U2 = 6, U3 = 7;
	private static final long ROUTE_DOWN = 10, ROUTE_UP = 11;
	private static final long[][] PLANS = {{0, 0}, {37000, 57000}, {94000, 114000}, {151000, 151000}};

	private static DutyGenerator.ServiceInput down(long minutes) {
		return new DutyGenerator.ServiceInput(ROUTE_DOWN, (int) (minutes * MINUTE), DepotDuties.ALL_WEEKDAYS, minutes * MINUTE, new long[]{A, D2, D3, B1}, PLANS, new boolean[4], 0, 0, -1, 0);
	}

	private static DutyGenerator.ServiceInput up(long minutes) {
		return new DutyGenerator.ServiceInput(ROUTE_UP, (int) (minutes * MINUTE), DepotDuties.ALL_WEEKDAYS, minutes * MINUTE, new long[]{B2, U2, U3, A}, PLANS, new boolean[4], 0, 0, -1, 0);
	}

	/**
	 * Every 10 minutes from 05:00 to 23:50, and a down service at 04:00, before any train can be at A.
	 */
	private static List<DutyGenerator.ServiceInput> services() {
		final List<DutyGenerator.ServiceInput> services = new ArrayList<>();
		services.add(down(4 * 60));
		for (long t = 5 * 60; t <= 23 * 60 + 50; t += 10) {
			services.add(down(t));
			services.add(up(t + 3));
		}
		return services;
	}

	private static Map<Long, Long> stations() {
		final Map<Long, Long> stationOf = new HashMap<>();
		stationOf.put(A, 100L);
		stationOf.put(B1, 200L);
		stationOf.put(B2, 200L);
		stationOf.put(D2, 300L);
		stationOf.put(U2, 300L);
		stationOf.put(D3, 400L);
		stationOf.put(U3, 400L);
		return stationOf;
	}

	private static DutyGenerator.PlanInputs inputs(Map<String, Integer> poolFleets) {
		final Map<Long, Map<Long, Long>> backLinks = new HashMap<>();
		final Map<Long, Long> back = new HashMap<>();
		back.put(B1, 98174L);
		back.put(A, OperationNetwork.UNREACHABLE);
		backLinks.put(1L, back);
		final Map<Long, Map<Long, Long>> turnTimes = new HashMap<>();
		turnTimes.put(B1, new HashMap<>(Collections.singletonMap(B2, 108224L)));
		return new DutyGenerator.PlanInputs(turnTimes, backLinks, poolFleets, transitsim.plan.Buffers.NONE, 30000);
	}

	private static DutyGenerator.SidingInput siding(int trains) {
		final DutyGenerator.SidingInput siding = new DutyGenerator.SidingInput(1, trains, Collections.singletonMap(B2, 88590L), 9);
		siding.poolKey = "r211/3";
		return siding;
	}

	@Test
	void aServiceNoTrainCanGetToDoesntHaveTheOthersSpread() {
		final DutyGenerator.Result result = DutyGenerator.generateSpread(services(), Collections.singletonList(siding(256)), stations(), RouteTimetable.MAX_STAY_MILLIS, inputs(Collections.emptyMap()));
		assertEquals(7, result.uncovered, "only the 04:00 service, each day");
		assertEquals(2, result.trains);
		assertEquals(7, DutyGenerator.unrunnable(services(), Collections.singletonList(siding(256)), stations(), RouteTimetable.MAX_STAY_MILLIS, inputs(Collections.emptyMap())));
	}

	@Test
	void tooFewTrainsStillHaveTheServicesSpread() {
		// One train for a line needing two: every other service of each route, evenly, and the 04:00 one no train runs
		final DutyGenerator.Result result = DutyGenerator.generateSpread(services(), Collections.singletonList(siding(1)), stations(), RouteTimetable.MAX_STAY_MILLIS, inputs(Collections.emptyMap()));
		assertEquals(1, result.trains);
		final int all = services().size() * 7;
		assertTrue(result.uncovered > 7 && result.uncovered < all, "some spread, not all: " + result.uncovered);
	}

	@Test
	void aPoolHeldToFewTrainsIsTrainsShortNotUnrunnable() {
		// The siding could hold any number, but its pool has one train: the services the second would run aren't unrunnable
		final DutyGenerator.PlanInputs inputs = inputs(Collections.singletonMap("r211/3", 1));
		assertEquals(7, DutyGenerator.unrunnable(services(), Collections.singletonList(siding(256)), stations(), RouteTimetable.MAX_STAY_MILLIS, inputs));
		final DutyGenerator.Result result = DutyGenerator.generateSpread(services(), Collections.singletonList(siding(256)), stations(), RouteTimetable.MAX_STAY_MILLIS, inputs);
		assertEquals(1, result.trains);
		assertTrue(result.uncovered > 7, "trains short: " + result.uncovered);
	}
}
