package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.plan.OperatingDay;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;

/**
 * A dispatcher's commands in the component's simulator (D6): a train held stays at its stop until let go, and carries on
 * from a save as running on does.
 */
class DispatchTest {

	private static final long START = MONDAY + 4 * HOUR + 50 * MINUTE;
	private static final long HOLD_AT = START + 90 * MINUTE;
	private static final long RELEASE_AT = HOLD_AT + 20 * MINUTE;
	private static final long END = RELEASE_AT + 40 * MINUTE;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	private static long firstTrain(EventSimulator<SimDataset.DataTrack> simulator) {
		return simulator.getRunner().getTrains().stream().filter(TickRunner.HeadlessTrain::isOnRoute).mapToLong(train -> train.id).min().orElse(0);
	}

	private static EventSimulator<SimDataset.DataTrack> run(byte[] bytes, long until, long[] held) {
		final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(SimDataset.start(bytes, START));
		simulator.advanceTo(HOLD_AT - 1);
		held[0] = firstTrain(simulator);
		simulator.submit(new HostInput.Dispatch(HOLD_AT, 1, held[0], TrainService.COMMAND_HOLD, 0, new long[0]));
		simulator.advanceTo(Math.min(until, RELEASE_AT - 1));
		if (until >= RELEASE_AT) {
			simulator.submit(new HostInput.Dispatch(RELEASE_AT, 2, held[0], TrainService.COMMAND_RELEASE, 0, new long[0]));
			simulator.advanceTo(until);
		}
		return simulator;
	}

	@Test
	void aHeldTrainStaysAtItsStopUntilLetGo() {
		final byte[] bytes = RestoreTest.byDuties().toBytes();
		final long[] held = new long[1];
		final EventSimulator<SimDataset.DataTrack> simulator = run(bytes, END, held);
		assertTrue(held[0] != 0, "a train was on the line");
		final List<SimEvent> events = simulator.events(0);
		// Within a few minutes it is at a stop, standing there or come to it, and leaves none while held
		final long settled = HOLD_AT + 5 * MINUTE;
		assertTrue(simulator.getRunner().getTrains().stream().anyMatch(train -> train.id == held[0]));
		assertEquals(0, events.stream().filter(event -> event.trainId == held[0] && event.kind == SimEvent.Kind.DEPART && event.time > settled && event.time < RELEASE_AT).count());
		// Let go, it goes on
		assertTrue(events.stream().anyMatch(event -> event.trainId == held[0] && event.kind == SimEvent.Kind.DEPART && event.time >= RELEASE_AT));
	}

	@Test
	void carryingOnFromARecordWhileHeldRunsAsRunningOnDoes() {
		final byte[] bytes = RestoreTest.byDuties().toBytes();
		final long[] held = new long[1];
		final List<String> expected = describe(run(bytes, END, held).events(0));
		final long saveAt = HOLD_AT + 10 * MINUTE + 350;
		final EventSimulator<SimDataset.DataTrack> saved = run(bytes, saveAt, new long[1]);
		final RunRecord record = RunRecord.fromBytes(saved.getRunner().record().toBytes());
		final EventSimulator<SimDataset.DataTrack> resumed = EventSimulator.resume(startMillis -> SimDataset.start(bytes, startMillis).get(), record, saveAt);
		resumed.advanceTo(RELEASE_AT - 1);
		resumed.submit(new HostInput.Dispatch(RELEASE_AT, 2, held[0], TrainService.COMMAND_RELEASE, 0, new long[0]));
		resumed.advanceTo(END);
		final List<String> expectedAfter = new ArrayList<>();
		expected.forEach(line -> {
			if (Long.parseLong(line.split(" ")[0]) > saveAt) {
				expectedAfter.add(line);
			}
		});
		final List<String> actual = new ArrayList<>();
		describe(resumed.events(0)).forEach(line -> {
			if (Long.parseLong(line.split(" ")[0]) > saveAt) {
				actual.add(line);
			}
		});
		assertEquals(expectedAfter, actual);
	}

	private static List<String> describe(List<SimEvent> events) {
		final List<String> lines = new ArrayList<>();
		events.stream().filter(event -> event.kind == SimEvent.Kind.ARRIVE || event.kind == SimEvent.Kind.DEPART || event.kind == SimEvent.Kind.RETURNED).forEach(event -> lines.add(event.time + " " + event.kind + " " + event.trainId + " " + event.rail));
		return lines;
	}
}
