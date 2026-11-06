package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.plan.OperatingDay;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;

/**
 * Trains of a depot running by duties told to pass stations without stopping (D3), in the component's simulator: they
 * pass them, stop again when told, and carry on from a save as running on does.
 */
class SkipStopsTest {

	private static final long START = MONDAY + 4 * HOUR + 50 * MINUTE;
	private static final long SKIP_AT = START + 90 * MINUTE;
	private static final long STOP_AGAIN_AT = START + 150 * MINUTE;
	private static final long END = START + 4 * HOUR;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	/**
	 * @return every platform the trains of the line run along
	 */
	private static long[] platforms(EventSimulator<SimDataset.DataTrack> simulator) {
		final Set<Long> platformIds = new TreeSet<>();
		simulator.getRunner().getTrains().forEach(train -> train.getPath().forEach(track -> {
			if (track.isPlatformTrack() && track.getStopId() != 0) {
				platformIds.add(track.getStopId());
			}
		}));
		return platformIds.stream().mapToLong(Long::longValue).toArray();
	}

	/**
	 * Tells every train to pass every platform it can at {@link #SKIP_AT}, and to stop again at {@link #STOP_AGAIN_AT}.
	 */
	private static EventSimulator<SimDataset.DataTrack> run(byte[] bytes, long until) {
		final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(SimDataset.start(bytes, START));
		simulator.advanceTo(SKIP_AT - 1);
		submitAll(simulator, SKIP_AT, platforms(simulator), 1);
		if (until <= STOP_AGAIN_AT) {
			simulator.advanceTo(until);
			return simulator;
		}
		simulator.advanceTo(STOP_AGAIN_AT - 1);
		submitAll(simulator, STOP_AGAIN_AT, new long[0], 1000);
		simulator.advanceTo(until);
		return simulator;
	}

	private static void submitAll(EventSimulator<SimDataset.DataTrack> simulator, long time, long[] platformIds, long sequence) {
		final List<Long> trainIds = new ArrayList<>();
		simulator.getRunner().getTrains().forEach(train -> trainIds.add(train.id));
		trainIds.sort(Long::compare);
		for (int i = 0; i < trainIds.size(); i++) {
			simulator.submit(new HostInput.Skip(time, sequence + i, trainIds.get(i), platformIds));
		}
	}

	private static long arrivals(List<SimEvent> events, Set<Integer> rails, long from, long to) {
		return events.stream().filter(event -> event.kind == SimEvent.Kind.ARRIVE && rails.contains(event.rail) && event.time >= from && event.time < to).count();
	}

	private static long count(List<SimEvent> events, SimEvent.Kind kind, long from, long to) {
		return events.stream().filter(event -> event.kind == kind && event.time >= from && event.time < to).count();
	}

	@Test
	void toldTrainsPassStationsAndStopAgainWhenToldTo() {
		final byte[] bytes = RestoreTest.byDuties().toBytes();
		final EventSimulator<SimDataset.DataTrack> simulator = run(bytes, END);
		final List<SimEvent> events = simulator.events(0);
		// The stops between the two ends of each route: platforms 2 and 5 of the line
		final Set<Integer> middle = new TreeSet<>();
		simulator.getRunner().getTrains().forEach(train -> {
			final List<SimDataset.DataTrack> trainPath = train.getPath();
			for (int i = 0; i < trainPath.size(); i++) {
				if (trainPath.get(i).getStopId() == 2 || trainPath.get(i).getStopId() == 5) {
					middle.add(i);
				}
			}
		});
		assertTrue(arrivals(events, middle, START, SKIP_AT) > 0, "before, trains stop there");
		// Told to, they pass them once the stop they are at or coming to is behind them
		assertEquals(0, arrivals(events, middle, SKIP_AT + 15 * MINUTE, STOP_AGAIN_AT));
		assertTrue(count(events, SimEvent.Kind.ARRIVE, SKIP_AT + 15 * MINUTE, STOP_AGAIN_AT) > 0, "and stop at the others");
		// Told to stop again, they stop there again
		assertTrue(arrivals(events, middle, STOP_AGAIN_AT + 15 * MINUTE, END) > 0);
		simulator.getRunner().getTrains().forEach(train -> assertTrue(train.service.getSkipPlatforms().isEmpty()));
	}

	@Test
	void carryingOnFromARecordWhilePassingStationsRunsAsRunningOnDoes() {
		final byte[] bytes = RestoreTest.byDuties().toBytes();
		final List<String> expected = describe(run(bytes, STOP_AGAIN_AT).events(0));
		boolean anySkipping = false;
		for (long time = SKIP_AT + 7 * MINUTE + 350; time < STOP_AGAIN_AT - 5 * MINUTE; time += 11 * MINUTE + 1250) {
			final EventSimulator<SimDataset.DataTrack> saved = run(bytes, time);
			anySkipping |= saved.getRunner().getTrains().stream().anyMatch(train -> train.service.isSkipping());
			final RunRecord record = RunRecord.fromBytes(saved.getRunner().record().toBytes());
			final EventSimulator<SimDataset.DataTrack> resumed = EventSimulator.resume(startMillis -> SimDataset.start(bytes, startMillis).get(), record, time);
			resumed.advanceTo(STOP_AGAIN_AT);
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
		assertTrue(anySkipping);
	}

	private static List<String> describe(List<SimEvent> events) {
		final List<String> lines = new ArrayList<>();
		events.stream().filter(event -> event.kind == SimEvent.Kind.ARRIVE || event.kind == SimEvent.Kind.DEPART || event.kind == SimEvent.Kind.PASS || event.kind == SimEvent.Kind.RETURNED).forEach(event -> lines.add(event.time + " " + event.kind + " " + event.trainId + " " + event.rail));
		return lines;
	}
}
