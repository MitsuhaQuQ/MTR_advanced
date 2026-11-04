package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.plan.OperatingDay;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;
import static transitsim.sim.ScenarioLine.SECOND;

/**
 * Carrying on from the trains as the game saves them, and recovering after a long stop (G0, contracts document 6.3 and
 * 6.4). This is where it shows whether what the game saves of a train is enough (review A08).
 */
class RestoreTest {

	private static final long START = MONDAY + 4 * HOUR + 50 * MINUTE;
	private static final Set<SimEvent.Kind> KINDS = EnumSet.of(SimEvent.Kind.ARRIVE, SimEvent.Kind.DEPART, SimEvent.Kind.PASS, SimEvent.Kind.RETURNED);

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void carryingOnFromARecordRunsAsRunningOnDoes() {
		for (final SimDataset dataset : new SimDataset[]{SimDatasetTest.lines(1, 400, 2, 4, 10), SimDatasetTest.lines(3, 400, 2, 8, 3), byDuties(), SignalBlockTest.line(true)}) {
			assertCarriesOn(dataset.toBytes(), true);
		}
	}

	@Test
	void withoutTheCruisingCapATrainStoppingShortGetsThereAtAnotherTime() {
		// What the game saves now leaves it out, so G0 saves it as well (review A08)
		assertCarriesOn(SimDatasetTest.lines(3, 400, 2, 8, 3).toBytes(), false);
	}

	/**
	 * @param withCap false to leave the cruising cap out of the records, which should then make some run come out different
	 */
	private static void assertCarriesOn(byte[] bytes, boolean withCap) {
		boolean allSame = true;
		final long end = START + 3 * HOUR;
		final EventSimulator<SimDataset.DataTrack> runningOn = new EventSimulator<>(SimDataset.start(bytes, START));
		runningOn.advanceTo(end);
		// Moments with trains running, standing with doors open, waiting to leave and in the depot
		for (long time = START + 70 * MINUTE + 350; time < end - 10 * MINUTE; time += 17 * MINUTE + 1250) {
			final EventSimulator<SimDataset.DataTrack> saved = new EventSimulator<>(SimDataset.start(bytes, START));
			saved.advanceTo(time);
			final RunRecord record = RunRecord.fromBytes(saved.getRunner().record().toBytes());
			if (!withCap) {
				final List<RunRecord.TrainRecord> trains = new ArrayList<>(record.trains);
				record.trains.clear();
				trains.forEach(train -> record.trains.add(new RunRecord.TrainRecord(train.sidingId, train.trainId, train.speed, train.railProgress, train.elapsedDwellTicks, train.nextStoppingIndex, train.nextPlatformIndex, train.reversed, train.onRoute, train.service, Float.NaN)));
			}
			final EventSimulator<SimDataset.DataTrack> resumed = EventSimulator.resume(startMillis -> SimDataset.start(bytes, startMillis).get(), record, time);
			resumed.advanceTo(end);
			final List<String> expected = describe(runningOn.events(0), time);
			final List<String> actual = describe(resumed.events(0), time);
			int first = 0;
			while (first < Math.min(expected.size(), actual.size()) && expected.get(first).equals(actual.get(first))) {
				first++;
			}
			if (withCap) {
				assertEquals(expected.size(), first, "saved at " + ScenarioLine.clock(time) + ", first difference: expected " + expected.subList(first, Math.min(expected.size(), first + 6)) + " but was " + actual.subList(first, Math.min(actual.size(), first + 6)));
			}
			allSame &= first == expected.size() && expected.size() == actual.size();
		}
		assertEquals(withCap, allSame);
	}

	/**
	 * A line whose depot runs by the duties the planner works out, with their paths.
	 */
	static SimDataset byDuties() {
		final SimDataset dataset = SimDatasetTest.lines(1, 400, 2, 4, 10);
		final transitsim.planning.DutyGenerator.Result result = transitsim.planning.PlanCheck.generate(dataset, 1, 0);
		PlanCheckTest.addDutyPaths(dataset, result, false);
		final transitsim.plan.DepotDuties duties = new transitsim.plan.DepotDuties();
		duties.diagramMode = true;
		duties.sidings.putAll(result.sidings);
		dataset.replaceDuties(1, duties.toBytes());
		return dataset;
	}

	@Test
	void afterALongStopTrainsGoWhereThePlanHasThem() {
		final byte[] bytes = SimDatasetTest.lines(3, 400, 2, 8, 3).toBytes();
		final long savedAt = START + 2 * HOUR;
		final EventSimulator<SimDataset.DataTrack> saved = new EventSimulator<>(SimDataset.start(bytes, START));
		saved.advanceTo(savedAt);
		final RunRecord record = saved.getRunner().record();
		final long now = savedAt + 2 * HOUR;
		final EventSimulator<SimDataset.DataTrack> resumed = EventSimulator.resume(startMillis -> SimDataset.start(bytes, startMillis).get(), record, now);
		resumed.advanceTo(now + 30 * SECOND);

		final List<SimEvent> events = resumed.events(0);
		final List<SimEvent> recovered = events.stream().filter(event -> event.kind == SimEvent.Kind.RECOVERED).collect(Collectors.toList());
		assertEquals(1, recovered.size(), describe(events, 0).toString());
		assertEquals(record.trains.size(), recovered.get(0).reason.split(", ").length, recovered.get(0).reason);
		// Nothing of the time missed happens
		assertTrue(events.stream().allMatch(event -> event.time >= now), describe(events, 0).toString());
		// No two trains of a siding stand in the same place
		final Set<String> places = new HashSet<>();
		for (final TickRunner<SimDataset.DataTrack>.HeadlessTrain train : resumed.getRunner().getTrains()) {
			if (train.isOnRoute()) {
				assertTrue(places.add(train.service.getTripRouteId() + "@" + Math.round(train.getRailProgress())), "two trains at " + train.getRailProgress());
			}
		}
		// And the line runs on
		resumed.advanceTo(now + 20 * MINUTE);
		assertTrue(resumed.events(0).stream().filter(event -> event.kind == SimEvent.Kind.ARRIVE).count() > 10);
	}

	private static List<String> describe(List<SimEvent> events, long after) {
		final List<String> lines = new ArrayList<>();
		events.stream().filter(event -> event.time > after && (after == 0 || KINDS.contains(event.kind))).forEach(event -> lines.add(ScenarioLine.clock(event.time) + " " + event.kind + " " + event.trainId + " " + event.rail));
		return lines;
	}
}
