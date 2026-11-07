package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.plan.Contingency;
import transitsim.plan.OperatingDay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;

/**
 * Trains of a depot running by duties told to turn back early (D4), in the component's simulator: up the line they turn
 * back at its middle station onto the down route, go on with their duties, and carry on from a save as running on does.
 */
class EarlyTurnTest {

	private static final long START = MONDAY + 4 * HOUR + 50 * MINUTE;
	private static final long TURN_AT = START + 90 * MINUTE;
	private static final long STOP_AT = START + 150 * MINUTE;
	private static final long END = START + 4 * HOUR;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	/**
	 * The line of {@link RestoreTest#byDuties()}, with a way round from the up platform of its middle station (rail 4 of
	 * the siding's path) to the down one (rail 9), as path generation would find.
	 */
	static byte[] withWayRound() {
		final SimDataset dataset = RestoreTest.byDuties();
		final SimDataset.SidingData siding = dataset.sidings.get(0);
		final SimDataset.DataTrack up = siding.path.get(4);
		final SimDataset.DataTrack down = siding.path.get(9);
		siding.addTurnLink(up.getStopId(), down.getStopId(), Arrays.asList(up, SimDatasetTest.plain(up.getEndNode(), down.getStartNode(), 30), down));
		return dataset.toBytes();
	}

	private static EventSimulator<SimDataset.DataTrack> run(byte[] bytes, long until) {
		final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(SimDataset.start(bytes, START));
		simulator.advanceTo(TURN_AT - 1);
		submitAll(simulator, TURN_AT, Contingency.TURN_NEAREST, 1);
		if (until <= STOP_AT) {
			simulator.advanceTo(until);
			return simulator;
		}
		simulator.advanceTo(STOP_AT - 1);
		submitAll(simulator, STOP_AT, 0, 1000);
		simulator.advanceTo(until);
		return simulator;
	}

	private static void submitAll(EventSimulator<SimDataset.DataTrack> simulator, long time, int mode, long sequence) {
		final List<Long> trainIds = new ArrayList<>();
		simulator.getRunner().getTrains().forEach(train -> trainIds.add(train.id));
		trainIds.sort(Long::compare);
		for (int i = 0; i < trainIds.size(); i++) {
			simulator.submit(new HostInput.TurnBack(time, sequence + i, trainIds.get(i), mode, new long[0], Contingency.AFTER_RESUME));
		}
	}

	@Test
	void toldTrainsTurnBackAtTheMiddleAndGoOnWithTheirDuties() {
		final byte[] bytes = withWayRound();
		final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(SimDataset.start(bytes, START));
		simulator.advanceTo(TURN_AT - 1);
		submitAll(simulator, TURN_AT, Contingency.TURN_NEAREST, 1);
		boolean anyTurned = false;
		boolean anyEmpty = false;
		for (long time = TURN_AT; time < STOP_AT; time += 1000) {
			simulator.advanceTo(time);
			for (final TickRunner<SimDataset.DataTrack>.HeadlessTrain train : simulator.getRunner().getTrains()) {
				anyTurned |= train.service.getTurnState() == 2;
				anyEmpty |= train.service.isOutOfService();
			}
		}
		assertTrue(anyTurned, "trains turn back");
		assertTrue(anyEmpty, "and run empty round");
		final long arrivals = simulator.events(0).stream().filter(event -> event.kind == SimEvent.Kind.ARRIVE && event.time > TURN_AT + 30 * MINUTE).count();
		assertTrue(arrivals > 0, "and go on with their duties");
	}

	@Test
	void carryingOnFromARecordWhileTurningBackRunsAsRunningOnDoes() {
		final byte[] bytes = withWayRound();
		final List<String> expected = describe(run(bytes, STOP_AT).events(0));
		boolean anyTurned = false;
		for (long time = TURN_AT + 7 * MINUTE + 350; time < STOP_AT - 5 * MINUTE; time += 9 * MINUTE + 1250) {
			final EventSimulator<SimDataset.DataTrack> saved = run(bytes, time);
			anyTurned |= saved.getRunner().getTrains().stream().anyMatch(train -> train.service.getTurnState() == 2);
			final RunRecord record = RunRecord.fromBytes(saved.getRunner().record().toBytes());
			final EventSimulator<SimDataset.DataTrack> resumed = EventSimulator.resume(startMillis -> SimDataset.start(bytes, startMillis).get(), record, time);
			resumed.advanceTo(STOP_AT);
			final long from = time;
			final List<String> expectedAfter = new ArrayList<>();
			expected.forEach(line -> {
				if (Long.parseLong(line.split(" ")[0]) > from) {
					expectedAfter.add(line);
				}
			});
			final List<String> actual = new ArrayList<>();
			describe(resumed.events(0)).forEach(line -> {
				if (Long.parseLong(line.split(" ")[0]) > from) {
					actual.add(line);
				}
			});
			assertEquals(expectedAfter, actual, "saved at " + ScenarioLine.clock(time));
		}
		assertTrue(anyTurned);
	}

	@Test
	void theWaysRoundAreReadBackAsWritten() {
		final SimDataset read = SimDataset.fromBytes(withWayRound());
		final SimDataset.SidingData siding = read.sidings.get(0);
		assertEquals(1, siding.turnLinks.size());
		assertEquals(Arrays.asList(5L), new ArrayList<>(siding.getTurnTargets(2)));
		assertEquals(3, siding.getTurnLink(2, 5).size());
	}

	private static List<String> describe(List<SimEvent> events) {
		final List<String> lines = new ArrayList<>();
		events.stream().filter(event -> event.kind == SimEvent.Kind.ARRIVE || event.kind == SimEvent.Kind.DEPART || event.kind == SimEvent.Kind.PASS || event.kind == SimEvent.Kind.RETURNED).forEach(event -> lines.add(event.time + " " + event.kind + " " + event.trainId + " " + event.rail));
		return lines;
	}
}
