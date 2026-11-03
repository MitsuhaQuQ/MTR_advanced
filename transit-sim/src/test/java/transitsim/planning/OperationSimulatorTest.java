package transitsim.planning;

import org.junit.jupiter.api.Test;
import transitsim.planning.OperationPlan.Duty;
import transitsim.planning.OperationPlan.IssueType;
import transitsim.planning.OperationPlan.Phase;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static transitsim.planning.OperationTime.ALL_DAYS;
import static transitsim.planning.OperationTime.DAY_MILLIS;
import static transitsim.planning.OperationTime.HOUR_MILLIS;
import static transitsim.planning.OperationTime.MINUTE_MILLIS;
import static transitsim.planning.OperationTime.SECOND_MILLIS;
import static transitsim.planning.OperationTime.WEEKDAYS;
import static transitsim.planning.OperationTime.WEEKENDS;
import static transitsim.planning.OperationTime.WEEK_MILLIS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationSimulatorTest {

	private static final long A = 1;
	private static final long B = 2;
	private static final long C = 3;
	private static final long DEPOT = 100;
	private static final long DWELL = 30 * SECOND_MILLIS;
	private static final long MAX_LAYOVER = 30 * MINUTE_MILLIS;

	private static TimetableService oneWay(long id, int days, long start, long from, long to, long running, boolean blank) {
		return new TimetableService(id, id, days, start, blank, Arrays.asList(
				new TimetableService.Stop(from, 0, DWELL, false),
				new TimetableService.Stop(to, DWELL + running, DWELL * 2 + running, false)
		));
	}

	/**
	 * A shuttle between two terminals, with one service each way per headway from {@code first} to {@code last}.
	 */
	private static List<TimetableService> shuttle(long firstId, int days, long first, long last, long headway, long running, long a, long b) {
		final List<TimetableService> services = new ArrayList<>();
		long id = firstId;
		for (long time = first; time <= last; time += headway) {
			services.add(oneWay(id++, days, time, a, b, running, false));
			services.add(oneWay(id++, days, time, b, a, running, false));
		}
		return services;
	}

	private static OperationNetwork network(int trains) {
		return new OperationNetwork()
				.setTurnaround(A, 2 * MINUTE_MILLIS)
				.setTurnaround(B, 2 * MINUTE_MILLIS)
				.setTurnaround(C, 2 * MINUTE_MILLIS)
				.addDepot(DEPOT, trains)
				.addDepotLink(DEPOT, A, 3 * MINUTE_MILLIS)
				.addDepotLink(DEPOT, B, 5 * MINUTE_MILLIS)
				.addDepotLink(DEPOT, C, 5 * MINUTE_MILLIS);
	}

	private static void assertNoIssue(OperationPlan plan) {
		final List<String> serious = plan.issues.stream().filter(issue -> issue.type != IssueType.UNUSED_BLANK).map(issue -> issue.type + " " + issue.detail).collect(Collectors.toList());
		assertEquals(Collections.emptyList(), serious);
	}

	private static int tripsIn(OperationPlan plan) {
		return plan.duties.stream().mapToInt(duty -> duty.trips.size()).sum();
	}

	@Test
	void fleetMatchesCycleTimeOnAShuttle() {
		// A trip takes 21 minutes and turnaround 2, and the next 10 minute slot is at 30, so a round trip uses 60 minutes
		final OperationPlan plan = DutyPlanner.plan(shuttle(1, ALL_DAYS, 6 * HOUR_MILLIS, 22 * HOUR_MILLIS, 10 * MINUTE_MILLIS, 20 * MINUTE_MILLIS, A, B), Collections.emptyList(), network(20), MAX_LAYOVER);
		assertNoIssue(plan);
		assertEquals(6, plan.peakTrains);
		assertEquals(97 * 2 * 7, tripsIn(plan));
	}

	@Test
	void planDoesNotDependOnInputOrder() {
		final List<TimetableService> services = shuttle(1, WEEKDAYS, 6 * HOUR_MILLIS, 23 * HOUR_MILLIS, 7 * MINUTE_MILLIS, 25 * MINUTE_MILLIS, A, B);
		services.addAll(shuttle(5000, WEEKENDS, 8 * HOUR_MILLIS, 23 * HOUR_MILLIS, 12 * MINUTE_MILLIS, 25 * MINUTE_MILLIS, A, B));
		final String expected = DutyPlanner.plan(services, Collections.emptyList(), network(30), MAX_LAYOVER).describe();

		final Random random = new Random(12345);
		for (int i = 0; i < 20; i++) {
			Collections.shuffle(services, random);
			assertEquals(expected, DutyPlanner.plan(services, Collections.emptyList(), network(30), MAX_LAYOVER).describe());
		}
	}

	@Test
	void tooFewTrainsLeavesTripsUnrun() {
		final OperationPlan plan = DutyPlanner.plan(shuttle(1, ALL_DAYS, 6 * HOUR_MILLIS, 10 * HOUR_MILLIS, 10 * MINUTE_MILLIS, 20 * MINUTE_MILLIS, A, B), Collections.emptyList(), network(4), MAX_LAYOVER);
		assertTrue(plan.hasIssue(IssueType.NO_TRAIN));
		assertTrue(plan.peakTrains <= 4);
	}

	@Test
	void lateNightTripRunsAcrossMidnightAndTheWeekBoundary() {
		// Sunday 23:50 to Monday 00:15 at B, then Monday 00:30 back to A
		final List<TimetableService> services = Arrays.asList(
				oneWay(1, 1 << 6, 23 * HOUR_MILLIS + 50 * MINUTE_MILLIS, A, B, 24 * MINUTE_MILLIS, false),
				oneWay(2, 1, 30 * MINUTE_MILLIS, B, A, 24 * MINUTE_MILLIS, false)
		);
		final OperationPlan plan = DutyPlanner.plan(services, Collections.emptyList(), network(2), MAX_LAYOVER);
		assertNoIssue(plan);
		assertEquals(2, plan.duties.size());
		assertEquals(1, plan.peakTrains);
		final Duty sunday = plan.duties.stream().filter(duty -> duty.trips.get(0).sourceId == 1).findFirst().orElseThrow(AssertionError::new);
		final Duty monday = plan.duties.stream().filter(duty -> duty.trips.get(0).sourceId == 2).findFirst().orElseThrow(AssertionError::new);
		assertEquals(monday.index, sunday.nextDuty);
		assertEquals(sunday.index, monday.previousDuty);
	}

	@Test
	void weekdayAndWeekendTablesJoinUp() {
		final List<TimetableService> services = shuttle(1, WEEKDAYS, 0, DAY_MILLIS - 10 * MINUTE_MILLIS, 10 * MINUTE_MILLIS, 20 * MINUTE_MILLIS, A, B);
		services.addAll(shuttle(5000, WEEKENDS, 0, DAY_MILLIS - 20 * MINUTE_MILLIS, 20 * MINUTE_MILLIS, 20 * MINUTE_MILLIS, A, B));
		final OperationPlan plan = DutyPlanner.plan(services, Collections.emptyList(), network(20), MAX_LAYOVER);
		assertNoIssue(plan);
		assertEquals(6, plan.peakTrains);
	}

	@Test
	void blankServiceOnlyRunsWhenAThroughTripTakesIt() {
		final OperationNetwork network = network(4).addConnection(B, C, MINUTE_MILLIS);
		final List<TimetableService> services = new ArrayList<>(Arrays.asList(
				oneWay(1, ALL_DAYS, 7 * HOUR_MILLIS, A, B, 20 * MINUTE_MILLIS, false),
				oneWay(2, ALL_DAYS, 7 * HOUR_MILLIS + 25 * MINUTE_MILLIS, B, C, 20 * MINUTE_MILLIS, true),
				oneWay(3, ALL_DAYS, 9 * HOUR_MILLIS, B, C, 20 * MINUTE_MILLIS, true)
		));
		final List<TimetableService.Through> throughs = Collections.singletonList(new TimetableService.Through(900, WEEKDAYS, Arrays.asList(1L, 2L)));
		final OperationPlan plan = DutyPlanner.plan(services, throughs, network, MAX_LAYOVER);

		assertNoIssue(plan);
		final long throughTrips = plan.duties.stream().flatMap(duty -> duty.trips.stream()).filter(trip -> trip.sourceId == 900).count();
		final long plainTrips = plan.duties.stream().flatMap(duty -> duty.trips.stream()).filter(trip -> trip.sourceId == 1).count();
		assertEquals(5, throughTrips);
		assertEquals(2, plainTrips);
		assertEquals(5 + 2, tripsIn(plan));
		assertEquals(2 + 7, plan.issues.stream().filter(issue -> issue.type == IssueType.UNUSED_BLANK).count());
	}

	@Test
	void throughTripTooTightIsAConflict() {
		final List<TimetableService> services = Arrays.asList(
				oneWay(1, ALL_DAYS, 7 * HOUR_MILLIS, A, B, 20 * MINUTE_MILLIS, false),
				oneWay(2, ALL_DAYS, 7 * HOUR_MILLIS + 21 * MINUTE_MILLIS, C, A, 20 * MINUTE_MILLIS, true)
		);
		final OperationPlan plan = DutyPlanner.plan(services, Collections.singletonList(new TimetableService.Through(900, ALL_DAYS, Arrays.asList(1L, 2L))), network(2).addConnection(B, C, 5 * MINUTE_MILLIS), MAX_LAYOVER);
		assertTrue(plan.hasIssue(IssueType.THROUGH_CONFLICT));
	}

	@Test
	void throughTripCanCrossMidnightIntoTheNextDaysService() {
		final List<TimetableService> services = Arrays.asList(
				oneWay(1, 1, 23 * HOUR_MILLIS + 30 * MINUTE_MILLIS, A, B, 20 * MINUTE_MILLIS, false),
				oneWay(2, 1 << 1, 5 * MINUTE_MILLIS, B, C, 20 * MINUTE_MILLIS, true)
		);
		final OperationPlan plan = DutyPlanner.plan(services, Collections.singletonList(new TimetableService.Through(900, 1, Arrays.asList(1L, 2L))), network(2).addConnection(B, C, MINUTE_MILLIS), MAX_LAYOVER);
		assertNoIssue(plan);
		assertEquals(1, tripsIn(plan));
		assertEquals(0, plan.issues.size());
	}

	@Test
	void circularLineKeepsOneTrainGoingLapAfterLap() {
		final List<TimetableService> laps = new ArrayList<>();
		for (int i = 0; i < 24 * 4; i++) {
			laps.add(new TimetableService(i + 1, 1, ALL_DAYS, i * 15 * MINUTE_MILLIS, false, Arrays.asList(
					new TimetableService.Stop(A, 0, DWELL, false),
					new TimetableService.Stop(B, 7 * MINUTE_MILLIS, 7 * MINUTE_MILLIS + DWELL, false),
					new TimetableService.Stop(A, 15 * MINUTE_MILLIS - DWELL, 15 * MINUTE_MILLIS - DWELL, false)
			)));
		}
		final OperationPlan plan = DutyPlanner.plan(laps, Collections.emptyList(), new OperationNetwork().addDepot(DEPOT, 3).addDepotLink(DEPOT, A, MINUTE_MILLIS), MAX_LAYOVER);
		assertNoIssue(plan);
		assertEquals(1, plan.peakTrains);
		assertEquals(1, plan.duties.size());
		assertEquals(0, plan.duties.get(0).nextDuty);
		assertEquals(0, plan.duties.get(0).previousDuty);
	}

	@Test
	void trainCannotWaitOnAPlatformAnotherTrainNeeds() {
		// The 08:30 arrival at B falls inside the long wait, so the first train cannot wait there for the 09:00
		final List<TimetableService> services = Arrays.asList(
				oneWay(1, ALL_DAYS, 8 * HOUR_MILLIS, A, B, 10 * MINUTE_MILLIS, false),
				oneWay(2, ALL_DAYS, 8 * HOUR_MILLIS + 19 * MINUTE_MILLIS, A, B, 10 * MINUTE_MILLIS, false),
				oneWay(3, ALL_DAYS, 8 * HOUR_MILLIS + 40 * MINUTE_MILLIS, B, A, 10 * MINUTE_MILLIS, false)
		);
		final OperationPlan plan = DutyPlanner.plan(services, Collections.emptyList(), network(3), HOUR_MILLIS);
		assertNoIssue(plan);
		final Duty lateDuty = plan.duties.stream().filter(duty -> duty.trips.get(0).sourceId == 2).findFirst().orElseThrow(AssertionError::new);
		assertEquals(Arrays.asList(2L, 3L), lateDuty.trips.stream().map(trip -> trip.sourceId).collect(Collectors.toList()));
	}

	@Test
	void timetableClashIsReported() {
		final List<TimetableService> services = Arrays.asList(
				oneWay(1, ALL_DAYS, 8 * HOUR_MILLIS, A, B, 10 * MINUTE_MILLIS, false),
				oneWay(2, ALL_DAYS, 8 * HOUR_MILLIS + 10 * SECOND_MILLIS, A, B, 10 * MINUTE_MILLIS, false)
		);
		assertTrue(DutyPlanner.plan(services, Collections.emptyList(), network(3), MAX_LAYOVER).hasIssue(IssueType.PLATFORM_CONFLICT));
	}

	@Test
	void everyDutyIsContinuousInTime() {
		final List<TimetableService> services = shuttle(1, WEEKDAYS, 5 * HOUR_MILLIS, 23 * HOUR_MILLIS + 50 * MINUTE_MILLIS, 10 * MINUTE_MILLIS, 20 * MINUTE_MILLIS, A, B);
		services.addAll(shuttle(5000, WEEKENDS, 7 * HOUR_MILLIS, 23 * HOUR_MILLIS, 20 * MINUTE_MILLIS, 20 * MINUTE_MILLIS, A, B));
		final OperationPlan plan = DutyPlanner.plan(services, Collections.emptyList(), network(10), MAX_LAYOVER);
		assertNoIssue(plan);
		assertTrue(plan.duties.size() > 7);

		plan.duties.forEach(duty -> {
			for (int i = 0; i < duty.phases.size(); i++) {
				final Phase phase = duty.phases.get(i);
				assertTrue(phase.startMillis <= phase.endMillis, phase.type + " runs backwards");
				if (i > 0) {
					assertEquals(duty.phases.get(i - 1).endMillis, phase.startMillis, duty.index + " gap before " + phase.type);
				}
			}
		});
	}

	@Test
	void daylightSavingJumpMovesTheWeekOffset() {
		final ZoneId zone = ZoneId.of("Europe/Berlin");
		// Sunday 2026-03-29 01:59:59 local, one second before clocks go forward
		final long before = 1774745999000L;
		final long after = before + 2 * SECOND_MILLIS;
		assertEquals(HOUR_MILLIS + 2 * SECOND_MILLIS, OperationTime.weekOffset(after, zone) - OperationTime.weekOffset(before, zone));
		assertEquals(after - SECOND_MILLIS, OperationTime.plannedTimestamp(before, zone, OperationTime.weekOffset(before, zone) + HOUR_MILLIS + SECOND_MILLIS));
	}

	@Test
	void largeNetworkPlansInReasonableTime() {
		final List<TimetableService> services = new ArrayList<>();
		final OperationNetwork network = new OperationNetwork();
		for (int line = 0; line < 12; line++) {
			final long a = 1000 + line * 10L;
			final long b = a + 1;
			network.setTurnaround(a, 2 * MINUTE_MILLIS).setTurnaround(b, 2 * MINUTE_MILLIS).addDepot(a, 40).addDepotLink(a, a, 3 * MINUTE_MILLIS).addDepotLink(a, b, 3 * MINUTE_MILLIS);
			services.addAll(shuttle(100000L * (line + 1), ALL_DAYS, 5 * HOUR_MILLIS, 24 * HOUR_MILLIS, 4 * MINUTE_MILLIS, 28 * MINUTE_MILLIS, a, b));
		}
		final long start = System.nanoTime();
		final OperationPlan plan = DutyPlanner.plan(services, Collections.emptyList(), network, MAX_LAYOVER);
		System.out.println(services.size() * 7 + " trips planned in " + (System.nanoTime() - start) / 1000000 + " ms, peak " + plan.peakTrains + " trains");
		assertNoIssue(plan);
	}
}
