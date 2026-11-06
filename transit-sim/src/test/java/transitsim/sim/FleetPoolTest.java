package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.plan.DepotDuties;
import transitsim.plan.OperatingDay;
import transitsim.planning.DutyGenerator;
import transitsim.planning.PlanCheck;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;

/**
 * The trains of a pool running by duties are the pool's (P2-C): it has as many as it is given in all, a train back in a
 * siding that already has one standing is kept in its shed and leaves later as the same train, and none is made in its
 * place.
 */
class FleetPoolTest {

	private static final long START = MONDAY + 5 * HOUR;
	private static final long END = MONDAY + 9 * HOUR;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void aPoolHasNoMoreTrainsThanItIsGiven() {
		for (final int fleet : new int[]{1, 2, 3}) {
			final TickRunner<SimDataset.DataTrack> runner = run(byDuties(fleet), true);
			final Set<Long> trains = departedTrains(runner);
			assertFalse(trains.isEmpty(), "no train left with " + fleet);
			assertTrue(trains.size() <= fleet, fleet + " in all, but " + trains + " ran");
		}
	}

	@Test
	void givenEnoughTrainsThePoolRunsAsAsManyAsNeeded() {
		final DutyGenerator.Result result = plan();
		final List<String> asNeeded = departures(run(byDuties(-1), true));
		final List<String> given = departures(run(byDuties(result.trains + 2), true));
		assertTrue(asNeeded.size() > 10, asNeeded.toString());
		assertEquals(asNeeded, given);
	}

	@Test
	void theSameTrainsRunTheWholeMorning() {
		// Each train back while another stands in the siding is kept and leaves again, rather than a new one made
		final DutyGenerator.Result result = plan();
		final TickRunner<SimDataset.DataTrack> runner = run(byDuties(result.trains + 1), true);
		final Set<Long> trains = departedTrains(runner);
		assertTrue(trains.size() <= result.trains + 1, trains + " for " + result.trains + " needed");
		final Map<Long, Long> departuresByTrain = runner.getEvents().stream().filter(event -> event.type == transitsim.motion.MotionEvent.Type.DEPARTED).collect(Collectors.groupingBy(event -> event.trainId, Collectors.counting()));
		assertTrue(departuresByTrain.values().stream().anyMatch(count -> count > 6), "no train ran more than one duty: " + departuresByTrain);
	}

	@Test
	void skippingTicksKeepsTheSameTrains() {
		final SimDataset dataset = byDuties(plan().trains + 1);
		assertEquals(describe(run(dataset, false)), describe(run(dataset, true)));
	}

	@Test
	void theShedsAreRecorded() {
		final RunRecord record = new RunRecord(5);
		record.sheds.put(100L, Arrays.asList(7L, 3L, 9L));
		final RunRecord read = RunRecord.fromBytes(record.toBytes());
		assertEquals(Arrays.asList(7L, 3L, 9L), read.sheds.get(100L));
	}

	@Test
	void aPoolsFleetIsSavedWithTheDataset() {
		final SimDataset dataset = SimDatasetTest.lines(1, 400, 2, 4, 10);
		dataset.sidings.get(0).poolFleet = 6;
		assertEquals(6, SimDataset.fromBytes(dataset.toBytes()).sidings.get(0).poolFleet);
		assertEquals(-1, SimDataset.fromBytes(SimDatasetTest.lines(1, 400, 2, 4, 10).toBytes()).sidings.get(0).poolFleet);
	}

	private static DutyGenerator.Result plan() {
		return PlanCheck.generate(SimDatasetTest.lines(1, 400, 2, 4, 10), 1, 0);
	}

	/**
	 * The line of {@link SimDatasetTest#lines} running by duties, its pool given a fleet in all.
	 */
	static SimDataset byDuties(int fleet) {
		final SimDataset dataset = RestoreTest.byDuties();
		dataset.sidings.forEach(siding -> siding.poolFleet = fleet);
		return dataset;
	}

	private static TickRunner<SimDataset.DataTrack> run(SimDataset dataset, boolean skipping) {
		final TickRunner<SimDataset.DataTrack> runner = SimDataset.start(dataset.toBytes(), START).get();
		runner.setSkipping(skipping);
		runner.runUntil(END);
		return runner;
	}

	private static Set<Long> departedTrains(TickRunner<SimDataset.DataTrack> runner) {
		return runner.getEvents().stream().filter(event -> event.type == transitsim.motion.MotionEvent.Type.DEPARTED).map(event -> event.trainId).collect(Collectors.toCollection(TreeSet::new));
	}

	/**
	 * @return where and when trains left, without which train it was
	 */
	private static List<String> departures(TickRunner<SimDataset.DataTrack> runner) {
		return runner.getEvents().stream().filter(event -> event.type == transitsim.motion.MotionEvent.Type.DEPARTED).map(event -> event.rail + " " + ScenarioLine.clock(event.millis)).sorted().collect(Collectors.toList());
	}

	private static List<String> describe(TickRunner<SimDataset.DataTrack> runner) {
		return runner.getEvents().stream().map(event -> event.trainId + " " + event.rail + " " + event.type + " " + ScenarioLine.clock(event.millis)).collect(Collectors.toList());
	}
}
