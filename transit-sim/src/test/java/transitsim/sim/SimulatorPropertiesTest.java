package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.motion.MotionEvent;
import transitsim.plan.OperatingDay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;
import static transitsim.sim.ScenarioLine.SECOND;

/**
 * The properties of decoupling document 7.3 and contracts document sections 2 and 7 the simulator must have (S4c).
 */
class SimulatorPropertiesTest {

	private static final long END = MONDAY + 7 * HOUR;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void howTheHostStepsDoesNotChangeTheResult() {
		final EventSimulator<ScenarioTrack> once = simulator();
		once.advanceTo(END);
		final EventSimulator<ScenarioTrack> inSteps = simulator();
		final Random random = new Random(1);
		while (inSteps.getTime() < END) {
			// Steps of any length, also shorter than a tick and not on one
			inSteps.advanceTo(Math.min(END, inSteps.getTime() + random.nextInt(20000)));
		}
		assertEquals(describe(once.events(0)), describe(inSteps.events(0)));
		assertEquals(once.query(1), inSteps.query(1));
	}

	@Test
	void timeOnlyGoesForward() {
		final EventSimulator<ScenarioTrack> simulator = simulator();
		simulator.advanceTo(MONDAY + 6 * HOUR);
		final long time = simulator.getTime();
		assertEquals(time, simulator.advanceTo(MONDAY + 5 * HOUR));
		assertEquals(time, simulator.getTime());
	}

	@Test
	void queriesChangeNothing() {
		final EventSimulator<ScenarioTrack> quiet = simulator();
		quiet.advanceTo(END);
		final EventSimulator<ScenarioTrack> asked = simulator();
		for (long time = MONDAY + 5 * HOUR + 50 * MINUTE; time < END; time += 7 * SECOND) {
			asked.advanceTo(time);
			final TrainState state = asked.query(1);
			assertEquals(state, asked.query(1));
			asked.getSegment(1);
			asked.events(0);
		}
		asked.advanceTo(END);
		assertEquals(describe(quiet.events(0)), describe(asked.events(0)));
	}

	@Test
	void inputsTakeEffectInTheirOrderHoweverTheyAreHandedIn() {
		final List<List<String>> results = new ArrayList<>();
		for (final List<Integer> order : Arrays.asList(Arrays.asList(0, 1, 2), Arrays.asList(2, 0, 1), Arrays.asList(1, 2, 0))) {
			final EventSimulator<ScenarioTrack> simulator = simulator();
			final List<HostInput> inputs = Arrays.asList(
					new HostInput.Hold(MONDAY + 6 * HOUR + 9 * MINUTE, 1, 1, "first"),
					new HostInput.Hold(MONDAY + 6 * HOUR + 9 * MINUTE, 2, 1, "second"),
					new HostInput.Resume(MONDAY + 6 * HOUR + 15 * MINUTE, 3, 1)
			);
			order.forEach(index -> simulator.submit(inputs.get(index)));
			simulator.advanceTo(END);
			results.add(describe(simulator.events(0)));
		}
		assertEquals(results.get(0), results.get(1));
		assertEquals(results.get(0), results.get(2));
		assertTrue(results.get(0).stream().anyMatch(line -> line.endsWith("HELD 1 second")));
	}

	@Test
	void eventsAreReadOnFromACursor() {
		final EventSimulator<ScenarioTrack> simulator = simulator();
		final List<SimEvent> read = new ArrayList<>();
		long cursor = 0;
		for (long time = MONDAY + 5 * HOUR + 50 * MINUTE; time <= END; time += MINUTE) {
			simulator.advanceTo(time);
			final List<SimEvent> newEvents = simulator.events(cursor);
			for (final SimEvent event : newEvents) {
				assertTrue(event.cursor > cursor);
				assertTrue(event.time <= simulator.getTime());
			}
			read.addAll(newEvents);
			if (!newEvents.isEmpty()) {
				cursor = newEvents.get(newEvents.size() - 1).cursor;
			}
		}
		assertEquals(describe(simulator.events(0)), describe(read));
	}

	@Test
	void aHeldTrainWaitsAtItsStopUntilItIsLetGo() {
		final EventSimulator<ScenarioTrack> simulator = simulator();
		// The 06:10 train is at its first stop from about 06:09:47
		simulator.submit(new HostInput.Hold(MONDAY + 6 * HOUR + 9 * MINUTE + 50 * SECOND, 1, 1, "player"));
		simulator.submit(new HostInput.Resume(MONDAY + 6 * HOUR + 13 * MINUTE, 2, 1));
		simulator.advanceTo(END);
		final List<String> events = describe(simulator.events(0));
		assertTrue(events.contains("06:09:50.0 HELD 1 player"));
		assertTrue(events.contains("06:13:00.0 RELEASED 1 "));
		final SimEvent departure = simulator.events(0).stream().filter(event -> event.kind == SimEvent.Kind.DEPART && event.rail == 2 && event.time > MONDAY + 6 * HOUR + 9 * MINUTE).findFirst().orElse(null);
		assertNotNull(departure);
		assertTrue(departure.time >= MONDAY + 6 * HOUR + 13 * MINUTE, ScenarioLine.clock(departure.time));
	}

	@Test
	void aRemovedTrainIsGone() {
		final EventSimulator<ScenarioTrack> simulator = simulator();
		simulator.advanceTo(MONDAY + 6 * HOUR + 2 * MINUTE);
		assertNotNull(simulator.query(1));
		simulator.submit(new HostInput.Remove(MONDAY + 6 * HOUR + 2 * MINUTE, 1, 1));
		simulator.advanceTo(MONDAY + 6 * HOUR + 3 * MINUTE);
		assertNull(simulator.query(1));
		// The siding makes another train, which takes the next services
		simulator.advanceTo(END);
		assertTrue(simulator.events(0).stream().anyMatch(event -> event.trainId != 1 && event.kind == SimEvent.Kind.DEPART));
	}

	@Test
	void aPlanPublishedWhileTrainsRunIsTheSameAsChangingItBetweenTicks() {
		final ScenarioLine line = new ScenarioLine(400, 2, 4);
		final EventSimulator<ScenarioTrack> simulator = new EventSimulator<>(line.runner);
		simulator.submit(new HostInput.PublishPlan(MONDAY + 6 * HOUR + 21 * MINUTE, 1, () -> {
			line.up.timetable.marginPercent = 40;
			line.up.timetable.revision++;
		}));
		simulator.advanceTo(END);
		final ScenarioLine edited = new ScenarioLine(400, 2, 4);
		edited.at(MONDAY + 6 * HOUR + 21 * MINUTE - 50, () -> {
			edited.up.timetable.marginPercent = 40;
			edited.up.timetable.revision++;
		});
		assertEquals(edited.run(END), toStops(simulator));
	}

	@Test
	void theSimulatorRunsTrainsAsTheTickRunnerDoes() {
		final EventSimulator<ScenarioTrack> simulator = new EventSimulator<>(new ScenarioLine(2400, 6, 6).runner);
		simulator.advanceTo(END);
		assertEquals(new ScenarioLine(2400, 6, 6).run(END), toStops(simulator));
	}

	private static EventSimulator<ScenarioTrack> simulator() {
		return new EventSimulator<>(new ScenarioLine(400, 2, 4).runner);
	}

	/**
	 * @return the departures, stops and returns as {@link ScenarioLine#run} gives them
	 */
	private static List<String> toStops(EventSimulator<ScenarioTrack> simulator) {
		final List<String> lines = new ArrayList<>();
		for (final MotionEvent event : simulator.getRunner().getEvents()) {
			if (event.type == MotionEvent.Type.STOPPED || event.type == MotionEvent.Type.DEPARTED || event.type == MotionEvent.Type.RETURNED) {
				lines.add(String.format("%d %d %s %s", event.trainId, event.rail, event.type, ScenarioLine.clock(event.millis)));
			}
		}
		return lines;
	}

	private static List<String> describe(List<SimEvent> events) {
		return Collections.unmodifiableList(events.stream().map(event -> ScenarioLine.clock(event.time) + " " + event.kind + " " + event.trainId + " " + event.reason).collect(Collectors.toList()));
	}
}
