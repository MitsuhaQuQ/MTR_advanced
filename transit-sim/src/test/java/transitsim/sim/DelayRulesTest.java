package transitsim.sim;

import org.junit.jupiter.api.Test;
import transitsim.plan.Contingencies;
import transitsim.plan.Contingency;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which plan a late or stuck train falls under, or whether it is only reported (D2).
 */
class DelayRulesTest {

	private static final long MINUTE = 60000;
	private static final long TRAIN = 7;
	private static final long ROUTE = 11;

	private static Contingency plan(long id, boolean manual, long... routeIds) {
		final Contingency plan = new Contingency();
		plan.id = id;
		plan.name = "plan " + id;
		plan.manual = manual;
		for (final long routeId : routeIds) {
			plan.routeIds.add(routeId);
		}
		return plan;
	}

	@Test
	void aPlanPassingStationsGivesTheirPlatformsUntilTheTrainIsAlmostOnTime() {
		final Contingencies contingencies = new Contingencies();
		final Contingency plan = plan(3, false, ROUTE);
		plan.lateMinutes = 3;
		plan.skipStationIds.add(50L);
		plan.skipStationIds.add(60L);
		contingencies.plans.put(plan.id, plan);
		final java.util.List<Contingencies> systems = java.util.Collections.singletonList(contingencies);
		final java.util.function.LongFunction<java.util.Collection<Long>> platforms = stationId -> stationId == 50 ? Arrays.asList(501L, 502L) : stationId == 60 ? java.util.Collections.singletonList(601L) : null;
		final java.util.Set<Long> all = new java.util.TreeSet<>(Arrays.asList(501L, 502L, 601L));

		final DelayRules.Outcome late = DelayRules.evaluate(systems, TRAIN, ROUTE, 3 * MINUTE, 0, false);
		assertEquals(all, DelayRules.getSkipPlatforms(systems, late, TRAIN, ROUTE, 3 * MINUTE, 0, false, false, platforms));
		// Under what starts the plan a train not passing stations doesn't start
		final DelayRules.Outcome less = DelayRules.evaluate(systems, TRAIN, ROUTE, 2 * MINUTE, 0, false);
		assertTrue(DelayRules.getSkipPlatforms(systems, less, TRAIN, ROUTE, 2 * MINUTE, 0, false, false, platforms).isEmpty());
		// One passing them goes on until it is less than a minute late
		assertEquals(all, DelayRules.getSkipPlatforms(systems, less, TRAIN, ROUTE, 2 * MINUTE, 0, false, true, platforms));
		final DelayRules.Outcome almost = DelayRules.evaluate(systems, TRAIN, ROUTE, MINUTE - 1, 0, false);
		assertTrue(DelayRules.getSkipPlatforms(systems, almost, TRAIN, ROUTE, MINUTE - 1, 0, false, true, platforms).isEmpty());

		// A plan waiting for a dispatcher passes nothing until it is started, and stops as soon as it is turned off
		plan.manual = true;
		final DelayRules.Outcome waiting = DelayRules.evaluate(systems, TRAIN, ROUTE, 9 * MINUTE, 0, false);
		assertTrue(DelayRules.getSkipPlatforms(systems, waiting, TRAIN, ROUTE, 9 * MINUTE, 0, false, true, platforms).isEmpty());
		contingencies.active.add(plan.id);
		final DelayRules.Outcome started = DelayRules.evaluate(systems, TRAIN, ROUTE, 9 * MINUTE, 0, false);
		assertEquals(all, DelayRules.getSkipPlatforms(systems, started, TRAIN, ROUTE, 9 * MINUTE, 0, false, false, platforms));

		// What a dispatcher sets for the train holds whatever it is
		final Contingency adhoc = new Contingency();
		adhoc.skipStationIds.add(60L);
		contingencies.adhoc.put(TRAIN, adhoc);
		final DelayRules.Outcome set = DelayRules.evaluate(systems, TRAIN, ROUTE, 0, 0, false);
		assertEquals(java.util.Collections.singleton(601L), DelayRules.getSkipPlatforms(systems, set, TRAIN, ROUTE, 0, 0, false, false, platforms));
	}

	@Test
	void withNoPlanALateTrainIsOnlyReported() {
		final DelayRules.Outcome onTime = DelayRules.evaluate(new Contingencies(), TRAIN, ROUTE, 4 * MINUTE, 0, false);
		assertNull(onTime.contingency);
		assertNull(onTime.alarm);
		final DelayRules.Outcome late = DelayRules.evaluate(new Contingencies(), TRAIN, ROUTE, 5 * MINUTE, 0, false);
		assertNull(late.contingency);
		assertEquals(Contingency.Reason.LATE, late.alarm);
		assertEquals(Contingency.Reason.WAITING, DelayRules.evaluate((Contingencies) null, TRAIN, ROUTE, 0, 10 * MINUTE, false).alarm);
		// The gravest first
		assertEquals(Contingency.Reason.DEADLOCK, DelayRules.evaluate((Contingencies) null, TRAIN, ROUTE, 9 * MINUTE, 11 * MINUTE, true).alarm);
	}

	@Test
	void anAutomaticPlanForTheRouteStartsByItself() {
		final Contingencies contingencies = new Contingencies();
		final Contingency plan = plan(3, false, ROUTE);
		plan.lateMinutes = 2;
		contingencies.plans.put(plan.id, plan);
		final DelayRules.Outcome outcome = DelayRules.evaluate(contingencies, TRAIN, ROUTE, 2 * MINUTE, 0, false);
		assertSame(plan, outcome.contingency);
		assertEquals(Contingency.Reason.LATE, outcome.reason);
		assertNull(outcome.alarm);
		// Not for another route, which with no plan of its own is reported by the default
		assertNull(DelayRules.affecting(contingencies, TRAIN, 12, 9 * MINUTE, 0, false));
		assertEquals(Contingency.Reason.LATE, DelayRules.evaluate(contingencies, TRAIN, 12, 9 * MINUTE, 0, false).alarm);
		// Under its own threshold a route with a plan isn't reported
		assertNull(DelayRules.evaluate(contingencies, TRAIN, ROUTE, MINUTE, 0, false).alarm);
	}

	@Test
	void aManualPlanReportsUntilADispatcherStartsIt() {
		final Contingencies contingencies = new Contingencies();
		contingencies.plans.put(4L, plan(4, true));
		final DelayRules.Outcome waiting = DelayRules.evaluate(contingencies, TRAIN, ROUTE, 6 * MINUTE, 0, false);
		assertNull(waiting.contingency);
		assertEquals(Contingency.Reason.LATE, waiting.alarm);
		assertEquals(4, waiting.waitingPlanId);
		contingencies.active.add(4L);
		assertSame(contingencies.plans.get(4L), DelayRules.affecting(contingencies, TRAIN, ROUTE, 6 * MINUTE, 0, false));
	}

	@Test
	void ofSeveralPlansTheLowestIdThatHoldsIsTaken() {
		final Contingencies contingencies = new Contingencies();
		final Contingency waitingOnly = plan(2, false);
		waitingOnly.lateMinutes = 0;
		contingencies.plans.put(9L, plan(9, false));
		contingencies.plans.put(2L, waitingOnly);
		contingencies.plans.put(5L, plan(5, false));
		assertEquals(5, DelayRules.affecting(contingencies, TRAIN, ROUTE, 6 * MINUTE, 0, false).id);
		assertEquals(2, DelayRules.affecting(contingencies, TRAIN, ROUTE, 6 * MINUTE, 10 * MINUTE, false).id);
	}

	@Test
	void systemsJoinedByRailsAllHoldTheFirstGivenFirst() {
		final Contingencies first = new Contingencies();
		final Contingency manual = plan(1, true);
		first.plans.put(1L, manual);
		final Contingencies second = new Contingencies();
		final Contingency automatic = plan(1, false);
		second.plans.put(1L, automatic);
		// The first system's plan waits for a dispatcher: the second's, with the same number, holds
		final DelayRules.Outcome outcome = DelayRules.evaluate(Arrays.asList(first, second), TRAIN, ROUTE, 6 * MINUTE, 0, false);
		assertSame(automatic, outcome.contingency);
		assertEquals(1, outcome.systemIndex);
		// Started, the first one's
		first.active.add(1L);
		assertEquals(0, DelayRules.evaluate(Arrays.asList(first, second), TRAIN, ROUTE, 6 * MINUTE, 0, false).systemIndex);
		// What is set for the train in either holds before any plan
		second.adhoc.put(TRAIN, plan(0, false));
		assertEquals(1, DelayRules.evaluate(Arrays.asList(first, second), TRAIN, ROUTE, 0, 0, false).systemIndex);
		// A plan waiting for a dispatcher says which system it is of
		first.active.clear();
		second.plans.clear();
		second.adhoc.clear();
		final DelayRules.Outcome waiting = DelayRules.evaluate(Arrays.asList(second, first), TRAIN, ROUTE, 6 * MINUTE, 0, false);
		assertEquals(1, waiting.systemIndex);
		assertEquals(1, waiting.waitingPlanId);
	}

	@Test
	void whatADispatcherSetForATrainHoldsWhateverItIs() {
		final Contingencies contingencies = new Contingencies();
		final Contingency adhoc = plan(0, false);
		adhoc.turn = Contingency.TURN_STATION;
		adhoc.turnStationId = 33;
		contingencies.adhoc.put(TRAIN, adhoc);
		assertSame(adhoc, DelayRules.affecting(contingencies, TRAIN, ROUTE, 0, 0, false));
		assertNull(DelayRules.affecting(contingencies, TRAIN + 1, ROUTE, 0, 0, false));
	}

	@Test
	void plansAndStateAreWrittenAndReadTheSame() {
		final Contingencies contingencies = new Contingencies();
		final Contingency first = plan(1, true, ROUTE, 12);
		first.name = "折返 B 站";
		first.turn = Contingency.TURN_STATION;
		first.turnStationId = 44;
		first.afterTurn = Contingency.AFTER_DEADHEAD;
		first.skipStationIds.add(55L);
		first.takeover = true;
		first.deadlock = false;
		contingencies.plans.put(1L, first);
		contingencies.plans.put(2L, plan(2, false));
		contingencies.active.add(1L);
		contingencies.adhoc.put(TRAIN, plan(0, false));
		final Contingencies read = new Contingencies();
		read.fromBytes(contingencies.toBytes());
		assertEquals(contingencies.plans, read.plans);
		assertEquals(contingencies.active, read.active);
		assertEquals(contingencies.adhoc, read.adhoc);
		assertEquals("折返 B 站", read.plans.get(1L).name);

		// A plan deleted is no longer on
		contingencies.plans.remove(1L);
		final transitsim.io.ByteArrayBinary.Writer writer = new transitsim.io.ByteArrayBinary.Writer();
		contingencies.writePlans(writer);
		read.readPlans(new transitsim.io.ByteArrayBinary.Reader(writer.toBytes()));
		assertTrue(read.active.isEmpty());

		// Bad bytes read as nothing
		read.fromBytes(new byte[]{1, 2, 3});
		assertTrue(read.isEmpty());
	}
}
