package transitsim.planning;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static transitsim.planning.OperationTime.ALL_DAYS;
import static transitsim.planning.OperationTime.HOUR_MILLIS;
import static transitsim.planning.OperationTime.MINUTE_MILLIS;
import static transitsim.planning.OperationTime.SECOND_MILLIS;

/**
 * First come, first out at a terminus with one way round (6号线, 2026-11-12): trains coming in at its eastbound platform U
 * turn back beyond it into its westbound platform D, and the depot's trains reach D the same way, through U and its turn
 * back. A depot train sent for a trip from D would come in behind a train already turning there and could not pass it.
 */
class DeadEndTerminusTest {

	private static final long A = 1;
	private static final long U = 2;
	private static final long D = 3;
	private static final long Z = 4;
	private static final long DEPOT = 100;
	private static final long DWELL = 30 * SECOND_MILLIS;
	private static final long RUNNING = 10 * MINUTE_MILLIS;

	private static TimetableService oneWay(long id, long start, long from, long to) {
		return new TimetableService(id, id, ALL_DAYS, start, false, Arrays.asList(
				new TimetableService.Stop(from, 0, DWELL, false),
				new TimetableService.Stop(to, DWELL + RUNNING, DWELL * 2 + RUNNING, false)
		));
	}

	private static OperationNetwork network(boolean depotThroughTheTurnBack) {
		final OperationNetwork network = new OperationNetwork()
				.setTurnaround(U, 30 * SECOND_MILLIS)
				.setTurnaround(D, 30 * SECOND_MILLIS)
				.addConnection(U, D, MINUTE_MILLIS)
				.addDepot(DEPOT, 3)
				.addDepotLink(DEPOT, A, MINUTE_MILLIS)
				.addDepotLink(DEPOT, D, 2 * MINUTE_MILLIS);
		return depotThroughTheTurnBack ? network.addDepotPassing(DEPOT, D, U) : network;
	}

	/**
	 * @return the services of the duty that runs the first one
	 */
	private static List<Long> afterTheFirst(boolean depotThroughTheTurnBack) {
		// In at U at 08:10:30; out of D at 08:11, too soon to turn round, and at 08:20
		final List<TimetableService> services = Arrays.asList(
				oneWay(1, 8 * HOUR_MILLIS, A, U),
				oneWay(2, 8 * HOUR_MILLIS + 11 * MINUTE_MILLIS, D, Z),
				oneWay(3, 8 * HOUR_MILLIS + 20 * MINUTE_MILLIS, D, Z)
		);
		final OperationPlan plan = DutyPlanner.plan(services, Collections.emptyList(), network(depotThroughTheTurnBack), 30 * MINUTE_MILLIS);
		return plan.duties.stream()
				.filter(duty -> duty.trips.stream().anyMatch(trip -> trip.sourceId == 1 && trip.startMillis() < OperationTime.DAY_MILLIS))
				.findFirst().map(duty -> duty.trips.stream().filter(trip -> trip.startMillis() < OperationTime.DAY_MILLIS).map(trip -> trip.sourceId).collect(Collectors.toList()))
				.orElse(Collections.emptyList());
	}

	private static OperationPlan everyFewSeconds(long headway) {
		final List<TimetableService> services = new java.util.ArrayList<>();
		for (int i = 0; i < 20; i++) {
			services.add(oneWay(10 + i, 8 * HOUR_MILLIS + i * headway, A, U));
			services.add(oneWay(100 + i, 8 * HOUR_MILLIS + 15 * MINUTE_MILLIS + i * headway, D, Z));
		}
		return DutyPlanner.plan(services, Collections.emptyList(), network(false).addDepot(DEPOT + 1, 40).addDepotLink(DEPOT + 1, A, MINUTE_MILLIS), 30 * MINUTE_MILLIS);
	}

	@Test
	void trainsComingInFasterThanTheyTurnRoundAreToldOf() {
		// Every 30 seconds into U, a minute to turn round to D (7号线, 2026-11-12)
		final OperationPlan plan = everyFewSeconds(30 * SECOND_MILLIS);
		org.junit.jupiter.api.Assertions.assertTrue(plan.hasIssue(OperationPlan.IssueType.TURN_BACK_CAPACITY), plan.issues.toString());
		org.junit.jupiter.api.Assertions.assertTrue(plan.issues.stream().anyMatch(issue -> issue.type == OperationPlan.IssueType.TURN_BACK_CAPACITY && issue.detail.equals(U + " " + 30 * SECOND_MILLIS + " " + MINUTE_MILLIS)), plan.issues.toString());
		org.junit.jupiter.api.Assertions.assertFalse(everyFewSeconds(5 * MINUTE_MILLIS).hasIssue(OperationPlan.IssueType.TURN_BACK_CAPACITY));
	}

	/**
	 * The waits of the duty running the first service: U's train waits 10 minutes for its next trip back from U; a train
	 * of another route ends at D 3 minutes in, both platforms turning round on one rail between them (8号线, 2026-11-12)
	 */
	private static List<Integer> waitsOfTheFirst(boolean sharedRail) {
		return waitsOfTheFirst(sharedRail, false);
	}

	private static List<Integer> waitsOfTheFirst(boolean sharedRail, boolean sameStation) {
		final OperationNetwork network = new OperationNetwork()
				.setTurnaround(U, 30 * SECOND_MILLIS)
				.setTurnaround(D, 30 * SECOND_MILLIS)
				.setTurnBackHold(U, 30 * SECOND_MILLIS, 30 * SECOND_MILLIS, 1, 77)
				.setTurnBackHold(D, 30 * SECOND_MILLIS, 30 * SECOND_MILLIS, 1, sharedRail ? 77 : 78)
				.addDepot(DEPOT, 4)
				.addDepotLink(DEPOT, A, MINUTE_MILLIS)
				.addDepotLink(DEPOT, Z, MINUTE_MILLIS);
		if (sameStation) {
			network.setStation(U, 50).setStation(D, 50);
		}
		final List<TimetableService> services = Arrays.asList(
				oneWay(1, 8 * HOUR_MILLIS, A, U),
				oneWay(2, 8 * HOUR_MILLIS + 21 * MINUTE_MILLIS, U, A),
				oneWay(3, 8 * HOUR_MILLIS + 3 * MINUTE_MILLIS, Z, D)
		);
		final OperationPlan plan = DutyPlanner.plan(services, Collections.emptyList(), network, 30 * MINUTE_MILLIS);
		return plan.duties.stream().filter(duty -> duty.trips.stream().anyMatch(trip -> trip.sourceId == 1 && trip.startMillis() < OperationTime.DAY_MILLIS)).findFirst().map(duty -> duty.waits).orElse(Collections.emptyList());
	}

	@Test
	void noLongWaitOnATurnBackRailAnotherTrainTurnsOnMeanwhile() {
		assertEquals(transitsim.plan.DepotDuties.DayDuty.WAIT_TURN_BACK_RAIL, (int) waitsOfTheFirst(false).get(0), "its own rail: waits there");
		org.junit.jupiter.api.Assertions.assertNotEquals(transitsim.plan.DepotDuties.DayDuty.WAIT_TURN_BACK_RAIL, (int) waitsOfTheFirst(true).get(0), "the rail D's train turns on: waits at the platform");
		// D's train reverses back into U's rail, though D isn't known to turn on it: the same station is enough
		org.junit.jupiter.api.Assertions.assertNotEquals(transitsim.plan.DepotDuties.DayDuty.WAIT_TURN_BACK_RAIL, (int) waitsOfTheFirst(false, true).get(0), "a train ends at the same station meanwhile");
	}

	@Test
	void theTrainTurningThereTakesTheNextTripThoughLate() {
		assertEquals(Arrays.asList(1L, 2L), afterTheFirst(true).subList(0, 2), "the train in first goes out first");
	}

	@Test
	void evenComingInJustAfterTheTripIsDueTheTrainThereFirstTakesIt() {
		// In at U at 08:10:30, the trip out of D due at 08:10: a depot train would come in behind it, later still
		final List<TimetableService> services = Arrays.asList(
				oneWay(1, 8 * HOUR_MILLIS, A, U),
				oneWay(2, 8 * HOUR_MILLIS + 10 * MINUTE_MILLIS, D, Z),
				oneWay(3, 8 * HOUR_MILLIS + 20 * MINUTE_MILLIS, D, Z)
		);
		final OperationPlan plan = DutyPlanner.plan(services, Collections.emptyList(), network(true), 30 * MINUTE_MILLIS);
		final List<Long> first = plan.duties.stream()
				.filter(duty -> duty.trips.stream().anyMatch(trip -> trip.sourceId == 1 && trip.startMillis() < OperationTime.DAY_MILLIS))
				.findFirst().map(duty -> duty.trips.stream().filter(trip -> trip.startMillis() < OperationTime.DAY_MILLIS).map(trip -> trip.sourceId).collect(Collectors.toList()))
				.orElse(Collections.emptyList());
		assertEquals(Arrays.asList(1L, 2L), first.subList(0, 2));
	}

	@Test
	void withAnotherWayFromTheDepotItWaitsForTheTripItCanMake() {
		assertEquals(Arrays.asList(1L, 3L), afterTheFirst(false).subList(0, 2), "a depot train takes 08:11, as before");
	}
}
