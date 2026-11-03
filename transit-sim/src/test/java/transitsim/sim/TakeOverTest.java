package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.plan.OperatingDay;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;
import static transitsim.sim.ScenarioLine.SECOND;

/**
 * Trains the simulation doesn't move, and trains the host takes over and gives back (G0, contracts document 3.1 rule 4),
 * on {@link ScenarioLine}'s terminal line: train 1 leaves the first platform at 06:00:10, stands at the far end from 06:01
 * until 06:05 and is back in the depot at 06:06; the next train runs the 06:10 service the same way.
 */
class TakeOverTest {

	private static final long SIX = MONDAY + 6 * HOUR;
	private static final long END = MONDAY + 6 * HOUR + 30 * MINUTE;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void aTrainWaitsForAnExternalTrainToClearTheRailsAhead() {
		// A train driven by hand stands between the second platform and the far end until 06:03
		final List<SimEvent> events = runBothWays(simulator -> {
			simulator.submit(new HostInput.ExternalTrain(SIX + 15 * SECOND, 1, 99, Collections.singleton(rail(5, 6))));
			simulator.submit(new HostInput.ExternalTrain(SIX + 3 * MINUTE, 2, 99, Collections.emptySet()));
		});
		// It waits at the second platform, as the rail after it is taken, and leaves once it is let go
		final SimEvent departure = events.stream().filter(event -> event.trainId == 1 && event.kind == SimEvent.Kind.DEPART && event.rail == 4).findFirst().orElseThrow(AssertionError::new);
		assertTrue(departure.time >= SIX + 3 * MINUTE && departure.time < SIX + 3 * MINUTE + 10 * SECOND, describe(events));
		final SimEvent arrival = events.stream().filter(event -> event.trainId == 1 && event.kind == SimEvent.Kind.ARRIVE && event.rail == 6).findFirst().orElseThrow(AssertionError::new);
		assertTrue(arrival.time > SIX + 3 * MINUTE, describe(events));
		// It still leaves the far end on time
		assertTrue(events.stream().anyMatch(event -> event.trainId == 1 && event.kind == SimEvent.Kind.DEPART && event.rail == 6 && event.time < SIX + 5 * MINUTE + 15 * SECOND), describe(events));
	}

	@Test
	void aTrainTakenOverStaysWhereItIsAndKeepsItsRails() {
		final double[] takenAt = new double[1];
		final EventSimulator<ScenarioTrack> simulator = newSimulator(true);
		simulator.advanceTo(SIX + 20 * SECOND);
		takenAt[0] = simulator.query(1).position;
		simulator.submit(new HostInput.TakeOver(SIX + 20 * SECOND, 1, 1));
		simulator.advanceTo(SIX + 11 * MINUTE);
		final TrainState taken = simulator.query(1);
		assertTrue(taken.takenOver);
		assertEquals(takenAt[0], taken.position, 1);
		// The next train can't leave the first platform onto the rail train 1 is on
		assertFalse(simulator.events(0).stream().anyMatch(event -> event.trainId == 2 && event.kind == SimEvent.Kind.DEPART && event.rail == 2), describe(simulator.events(0)));

		simulator.submit(new HostInput.Release(SIX + 11 * MINUTE, 2, 1, taken.position, 0));
		simulator.advanceTo(END);
		final List<SimEvent> events = simulator.events(0);
		assertFalse(simulator.query(1) != null && simulator.query(1).takenOver);
		assertTrue(events.stream().anyMatch(event -> event.trainId == 1 && event.kind == SimEvent.Kind.ARRIVE && event.rail == 4 && event.time > SIX + 11 * MINUTE), describe(events));
		assertTrue(events.stream().anyMatch(event -> event.trainId == 2 && event.kind == SimEvent.Kind.DEPART && event.rail == 2 && event.time > SIX + 11 * MINUTE), describe(events));
	}

	@Test
	void aTrainGivenBackElsewhereRunsOnFromThere() {
		final EventSimulator<ScenarioTrack> simulator = newSimulator(true);
		simulator.advanceTo(SIX + 20 * SECOND);
		simulator.submit(new HostInput.TakeOver(SIX + 20 * SECOND, 1, 1));
		simulator.advanceTo(SIX + MINUTE);
		final TickRunner<ScenarioTrack>.HeadlessTrain train = simulator.getRunner().getTrain(1);
		// Half way between the second platform and the far end
		final double position = (train.getDistance(4) + train.getDistance(5)) / 2;
		simulator.submit(new HostInput.Release(SIX + MINUTE, 2, 1, position, 0));
		simulator.advanceTo(SIX + MINUTE + 100);
		assertEquals(5, simulator.query(1).rail);
		simulator.advanceTo(END);
		final List<SimEvent> events = simulator.events(0).stream().filter(event -> event.trainId == 1 && event.time > SIX + MINUTE && event.time < SIX + 5 * MINUTE).collect(Collectors.toList());
		assertTrue(events.stream().noneMatch(event -> event.kind == SimEvent.Kind.ARRIVE && event.rail == 4), describe(events));
		assertTrue(events.stream().anyMatch(event -> event.kind == SimEvent.Kind.ARRIVE && event.rail == 6), describe(events));
	}

	@Test
	void takingOverGivesTheSameWhetherTicksAreSkippedOrNot() {
		runBothWays(simulator -> {
			simulator.submit(new HostInput.TakeOver(SIX + 20 * SECOND, 1, 1));
			simulator.submit(new HostInput.ExternalTrain(SIX + 5 * MINUTE, 2, 1, Collections.singleton(rail(3, 4))));
			simulator.submit(new HostInput.Release(SIX + 11 * MINUTE, 3, 1, 300, 0.2F));
		});
	}

	/**
	 * @return the events with ticks skipped, checked to be the same as running every tick
	 */
	private static List<SimEvent> runBothWays(Consumer<EventSimulator<ScenarioTrack>> inputs) {
		final EventSimulator<ScenarioTrack> everyTick = newSimulator(false);
		final EventSimulator<ScenarioTrack> skipping = newSimulator(true);
		inputs.accept(everyTick);
		inputs.accept(skipping);
		everyTick.advanceTo(END);
		skipping.advanceTo(END);
		assertEquals(describe(everyTick.events(0)), describe(skipping.events(0)));
		return skipping.events(0);
	}

	private static EventSimulator<ScenarioTrack> newSimulator(boolean skipping) {
		return new EventSimulator<>(() -> new ScenarioLine(400, 2, 4).runner, skipping);
	}

	private static java.util.UUID rail(long from, long to) {
		return ScenarioTrack.plain(from, to, 400, false).getTrackKey();
	}

	private static String describe(List<SimEvent> events) {
		return events.stream().map(event -> ScenarioLine.clock(event.time) + " " + event.kind + " " + event.trainId + " " + event.rail).collect(Collectors.joining(", "));
	}
}
