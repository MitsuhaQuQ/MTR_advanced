package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.plan.OperatingDay;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;
import static transitsim.sim.ScenarioLine.SECOND;

/**
 * Forks for predictions, snapshots to carry on from and changes to the network (contracts document 5.1, 6.2 and 6.4, S4d).
 */
class ForkSnapshotTest {

	private static final long END = MONDAY + 7 * HOUR;
	private static final Supplier<TickRunner<ScenarioTrack>> START = () -> new ScenarioLine(400, 2, 4).runner;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void aForkPredictsWhatHappens() {
		final EventSimulator<ScenarioTrack> simulator = new EventSimulator<>(START);
		simulator.advanceTo(MONDAY + 6 * HOUR + 5 * MINUTE);
		final EventSimulator<ScenarioTrack> fork = simulator.fork();
		assertEquals(simulator.getTime(), fork.getTime());
		assertEquals(simulator.query(1), fork.query(1));
		fork.advanceTo(END);
		simulator.advanceTo(END);
		assertEquals(describe(simulator.events(0)), describe(fork.events(0)));
		assertTrue(fork.events(0).stream().allMatch(event -> event.predicted));
		assertTrue(simulator.events(0).stream().noneMatch(event -> event.predicted));
	}

	@Test
	void whatHappensInAForkStaysThere() {
		final EventSimulator<ScenarioTrack> simulator = new EventSimulator<>(START);
		simulator.advanceTo(MONDAY + 6 * HOUR + 5 * MINUTE);
		final EventSimulator<ScenarioTrack> fork = simulator.fork();
		// What if the 06:10 train were held five minutes at its first stop, and the up line ran slower
		fork.submit(new HostInput.Hold(MONDAY + 6 * HOUR + 9 * MINUTE + 50 * SECOND, 1, 1, "what if"));
		fork.submit(new HostInput.Resume(MONDAY + 6 * HOUR + 14 * MINUTE + 50 * SECOND, 2, 1));
		fork.submit(new HostInput.PublishPlan(MONDAY + 6 * HOUR + 6 * MINUTE, 3, routes -> {
			routes.apply(1).getTimetable().marginPercent = 40;
			routes.apply(1).getTimetable().revision++;
		}));
		fork.advanceTo(END);
		simulator.advanceTo(END);
		final EventSimulator<ScenarioTrack> untouched = new EventSimulator<>(START);
		untouched.advanceTo(END);
		assertEquals(describe(untouched.events(0)), describe(simulator.events(0)));
		assertNotEquals(describe(simulator.events(0)), describe(fork.events(0)));
		assertEquals(5, simulator.getRunner().getRoutes().apply(1).getTimetable().marginPercent);
	}

	@Test
	void aSimulationCarriesOnFromItsSnapshot() {
		final EventSimulator<ScenarioTrack> simulator = new EventSimulator<>(START);
		simulator.submit(new HostInput.Hold(MONDAY + 6 * HOUR + 9 * MINUTE + 50 * SECOND, 1, 1, "player"));
		simulator.submit(new HostInput.Resume(MONDAY + 6 * HOUR + 40 * MINUTE, 2, 1));
		simulator.advanceTo(MONDAY + 6 * HOUR + 30 * MINUTE + 17);
		final SimulationSnapshot snapshot = simulator.snapshot();
		final EventSimulator<ScenarioTrack> restored = EventSimulator.restore(START, snapshot);
		assertEquals(simulator.getTime(), restored.getTime());
		assertEquals(simulator.query(1), restored.query(1));
		assertEquals(snapshot.eventCursor, restored.events(0).get(restored.events(0).size() - 1).cursor);
		// The resume still waiting goes with the snapshot
		simulator.advanceTo(END);
		restored.advanceTo(END);
		assertEquals(describe(simulator.events(0)), describe(restored.events(0)));
		assertFalse(restored.events(0).stream().anyMatch(event -> event.predicted));
	}

	@Test
	void aTrainStopsShortOfATrackRemovedAhead() {
		final EventSimulator<ScenarioTrack> simulator = new EventSimulator<>(START);
		// The 06:20 train stands at its first stop, the next track is taken out of the network
		simulator.submit(new HostInput.PublishNetwork(MONDAY + 6 * HOUR + 20 * MINUTE, 1, Collections.singleton(ScenarioTrack.plain(3, 4, 400, false).getTrackKey())));
		simulator.advanceTo(END);
		final List<SimEvent> events = simulator.events(0);
		assertTrue(events.stream().anyMatch(event -> event.kind == SimEvent.Kind.CONFLICT && event.reason.equals("track removed ahead") && event.rail == 3));
		// It never leaves the stop, and the siding sends no more trains over the removed track
		assertTrue(events.stream().noneMatch(event -> event.kind == SimEvent.Kind.DEPART && event.time > MONDAY + 6 * HOUR + 20 * MINUTE));
		assertEquals(2, simulator.query(1).rail);
	}

	@Test
	void aTrainOnARemovedTrackIsTakenOff() {
		final EventSimulator<ScenarioTrack> simulator = new EventSimulator<>(START);
		// The platform the 06:20 train stands at is taken out of the network
		simulator.submit(new HostInput.PublishNetwork(MONDAY + 6 * HOUR + 20 * MINUTE, 1, Collections.singleton(ScenarioTrack.platform(2, 3, 1, 20, false).getTrackKey())));
		simulator.advanceTo(MONDAY + 6 * HOUR + 21 * MINUTE);
		assertTrue(simulator.events(0).stream().anyMatch(event -> event.kind == SimEvent.Kind.CONFLICT && event.reason.equals("track removed under the train")));
		assertNull(simulator.query(1));
	}

	private static List<String> describe(List<SimEvent> events) {
		return events.stream().map(event -> event.cursor + " " + ScenarioLine.clock(event.time) + " " + event.kind + " " + event.trainId + " " + event.rail + " " + event.reason).collect(Collectors.toList());
	}
}
