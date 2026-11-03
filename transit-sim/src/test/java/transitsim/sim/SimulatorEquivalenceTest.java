package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.plan.OperatingDay;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;
import static transitsim.sim.ScenarioLine.SECOND;

/**
 * Skipping the ticks in which nothing can change gives the same events at the same times as running every tick (S4c2).
 */
class SimulatorEquivalenceTest {

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void aTerminalLine() {
		assertSame(400, 2, 4, MONDAY + 8 * HOUR + 30 * MINUTE, simulator -> {
		});
	}

	@Test
	void aLongLineWithLongTrains() {
		assertSame(2400, 6, 6, MONDAY + 8 * HOUR + 30 * MINUTE, simulator -> {
		});
	}

	@Test
	void aTrainHeldUpOnTheWay() {
		assertSame(400, 2, 4, MONDAY + 7 * HOUR, simulator -> simulator.submit(new HostInput.ExternalOccupancy(MONDAY + 6 * HOUR + 20 * MINUTE, 1, ScenarioTrack.plain(3, 4, 400, false).getTrackKey(), MONDAY + 6 * HOUR + 22 * MINUTE + 30 * SECOND)));
	}

	@Test
	void aTrainHeldAndLetGoByTheHost() {
		assertSame(2400, 6, 6, MONDAY + 7 * HOUR, simulator -> {
			simulator.submit(new HostInput.Hold(MONDAY + 6 * HOUR + 2 * MINUTE, 1, 1, "player"));
			simulator.submit(new HostInput.Resume(MONDAY + 6 * HOUR + 9 * MINUTE, 2, 1));
		});
	}

	@Test
	void mostTicksOfADayAreSkipped() {
		final long end = MONDAY + 23 * HOUR;
		final EventSimulator<ScenarioTrack> everyTick = new EventSimulator<>(() -> new ScenarioLine(2400, 6, 6).runner, false);
		final long start1 = System.nanoTime();
		everyTick.advanceTo(end);
		final long everyTickNanos = System.nanoTime() - start1;
		final EventSimulator<ScenarioTrack> skipping = new EventSimulator<>(() -> new ScenarioLine(2400, 6, 6).runner, true);
		final long start2 = System.nanoTime();
		skipping.advanceTo(end);
		final long skippingNanos = System.nanoTime() - start2;
		assertEquals(describe(everyTick.events(0)), describe(skipping.events(0)));
		final long ticks = (end - (MONDAY + 5 * HOUR + 50 * MINUTE)) / 50;
		final double skippedShare = (double) skipping.getRunner().getSkippedTicks() / ticks;
		System.out.printf("Day of a long line: every tick %d ms, skipping %d ms, %.1f%% of %d ticks skipped%n", everyTickNanos / 1000000, skippingNanos / 1000000, skippedShare * 100, ticks);
		assertTrue(skippedShare > 0.9, String.valueOf(skippedShare));
	}

	private static void assertSame(double between, int trainCars, int maxTrains, long end, Consumer<EventSimulator<ScenarioTrack>> inputs) {
		final EventSimulator<ScenarioTrack> everyTick = new EventSimulator<>(() -> new ScenarioLine(between, trainCars, maxTrains).runner, false);
		final EventSimulator<ScenarioTrack> skipping = new EventSimulator<>(() -> new ScenarioLine(between, trainCars, maxTrains).runner, true);
		inputs.accept(everyTick);
		inputs.accept(skipping);
		everyTick.advanceTo(end);
		skipping.advanceTo(end);
		assertEquals(describe(everyTick.events(0)), describe(skipping.events(0)));
		assertEquals(everyTick.query(1), skipping.query(1));
		assertTrue(skipping.getRunner().getSkippedTicks() > 0);
	}

	private static List<String> describe(List<SimEvent> events) {
		return events.stream().map(event -> ScenarioLine.clock(event.time) + " " + event.kind + " " + event.trainId + " " + event.rail + " " + event.reason).collect(Collectors.toList());
	}
}
