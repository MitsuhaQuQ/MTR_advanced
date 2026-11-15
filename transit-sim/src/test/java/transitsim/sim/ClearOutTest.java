package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.plan.OperatingDay;
import transitsim.plan.TripPath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;

/**
 * The dispatcher's "下一站清客后回库" in the component's simulator (0.84.11; 6-line test, 2026-11-15, line 9): told while
 * running to a stop, the train clears out at that stop, the next one the console shows, and opens its doors nowhere after
 * it on its way back empty; the rest of its duty is taken over by a spare from the depot, or, with none, told rather than
 * silently not run.
 */
class ClearOutTest {

	private static final long START = MONDAY + 4 * HOUR + 50 * MINUTE;
	/**
	 * The middle up platform's rail on the duty's path: the next stop when told.
	 */
	private static final int MIDDLE_UP = 4;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	private static final class Run {
		final EventSimulator<SimDataset.DataTrack> simulator;
		final RunInvariants<SimDataset.DataTrack> invariants;
		final long trainId;
		final long told;
		boolean spareOut;

		Run(byte[] bytes) {
			simulator = new EventSimulator<>(SimDataset.start(bytes, START));
			invariants = new RunInvariants<>(simulator.getRunner());
			// A train on the first segment of its duty, the up route, running to the middle up platform
			long time = MONDAY + 9 * HOUR;
			long found = 0;
			for (; time < MONDAY + 10 * HOUR && found == 0; time += 1000) {
				simulator.advanceTo(time);
				for (final TickRunner<SimDataset.DataTrack>.HeadlessTrain train : simulator.getRunner().getTrains()) {
					if (found == 0 && train.isOnRoute() && train.getSpeed() > 0 && train.service.getDutyIndex() == 0 && train.getNextStoppingIndex() == MIDDLE_UP) {
						found = train.id;
					}
				}
			}
			assertTrue(found != 0, "a train running to the middle up platform");
			trainId = found;
			told = time;
			simulator.submit(new HostInput.Dispatch(told, 1, trainId, TrainService.COMMAND_CLEAR_OUT, 0, new long[0]));
			simulator.submit(new HostInput.Dispatch(told, 2, trainId, TrainService.COMMAND_RETURN, 0, new long[0]));
		}

		void runFor(long millis, java.util.function.Consumer<TickRunner<SimDataset.DataTrack>.HeadlessTrain> each) {
			for (long time = told; time < told + millis; time += 1000) {
				simulator.advanceTo(time);
				invariants.check();
				for (final TickRunner<SimDataset.DataTrack>.HeadlessTrain train : simulator.getRunner().getTrains()) {
					spareOut |= train.isOnRoute() && TripPath.isFromKey(train.service.getTripPathKey());
					each.accept(train);
				}
			}
		}

		/**
		 * The cleared train's events from when it was told until it was back in its siding.
		 */
		List<SimEvent> clearedTrainEvents() {
			final List<SimEvent> events = new ArrayList<>();
			for (final SimEvent event : simulator.events(0)) {
				if (event.trainId == trainId && event.time >= told) {
					events.add(event);
					if (event.kind == SimEvent.Kind.RETURNED) {
						break;
					}
				}
			}
			return events;
		}
	}

	private static String describe(List<SimEvent> events) {
		return events.stream().map(event -> ScenarioLine.clock(event.time) + " " + event.kind + " " + event.rail).collect(Collectors.joining(", "));
	}

	@Test
	void clearedAtTheStopItRunsToItOpensItsDoorsNowhereAfterAndASpareRunsTheRest() {
		final Run run = new Run(WholeDayTest.dataset());
		final int[] takeoverState = new int[1];
		run.runFor(20 * MINUTE, train -> {
			if (train.id == run.trainId && train.isOnRoute() && train.service.getTakeoverState() != 0) {
				takeoverState[0] = train.service.getTakeoverState();
			}
		});
		final List<SimEvent> events = run.clearedTrainEvents();
		assertTrue(events.stream().anyMatch(event -> event.kind == SimEvent.Kind.RETURNED), describe(events));
		// The doors open at the stop it was running to, where everyone gets off, and at no stop after it (they opened again
		// one stop further before)
		final List<Integer> doorRails = events.stream().filter(event -> event.kind == SimEvent.Kind.DOORS_OPEN).map(event -> event.rail).collect(Collectors.toList());
		assertEquals(Collections.singletonList(MIDDLE_UP), doorRails, describe(events));
		// The down route its duty runs next is taken over by a spare out of the siding, and nothing is left unrun
		assertEquals(2, takeoverState[0]);
		assertTrue(run.spareOut, "a spare drives out for the rest of the duty");
		run.simulator.getRunner().getDepots().forEach(depot -> assertEquals(Collections.emptyList(), depot.uncoveredServices));
		assertEquals(Collections.emptyList(), run.invariants.getViolations());
	}

	@Test
	void withNoSpareToTakeTheRestOverItIsToldNotSilentlyLeft() {
		// No way out of the siding but to the first stop of the duty: no spare can get to the down route
		final SimDataset dataset = SimDataset.fromBytes(WholeDayTest.dataset());
		dataset.sidings.forEach(siding -> siding.wayOuts.clear());
		final Run run = new Run(dataset.toBytes());
		final int[] takeoverState = new int[1];
		run.runFor(10 * MINUTE, train -> {
			if (train.id == run.trainId && train.isOnRoute() && train.service.getTakeoverState() != 0) {
				takeoverState[0] = train.service.getTakeoverState();
			}
		});
		final List<SimEvent> events = run.clearedTrainEvents();
		assertEquals(Collections.singletonList(MIDDLE_UP), events.stream().filter(event -> event.kind == SimEvent.Kind.DOORS_OPEN).map(event -> event.rail).collect(Collectors.toList()), describe(events));
		assertFalse(run.spareOut);
		assertEquals(TrainService.TAKEOVER_NO_SPARE, takeoverState[0]);
		final List<String> uncovered = new ArrayList<>();
		run.simulator.getRunner().getDepots().forEach(depot -> uncovered.addAll(depot.uncoveredServices));
		assertEquals(1, uncovered.size(), uncovered.toString());
		// Segment 1, the down route, until the end of the duty
		assertTrue(uncovered.get(0).matches("\\d+ \\d+ -?\\d+ 1 2 .*"), uncovered.get(0));
		assertEquals(Collections.emptyList(), run.invariants.getViolations());
	}
}
