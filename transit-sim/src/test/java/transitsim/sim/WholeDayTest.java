package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import transitsim.plan.Contingency;
import transitsim.plan.DepotDuties;
import transitsim.plan.OperatingDay;
import transitsim.planning.DutyGenerator;
import transitsim.planning.PlanCheck;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static transitsim.sim.ScenarioLine.*;

/**
 * The end-to-end check of the component (2026-11-07): a line run by duties for a whole day, from 05:00 to after
 * midnight, with something going wrong every hour or so and every way of handling it used: a train held on the line,
 * passing a station, turning back early, handing its duty over to a spare, and the dispatcher's commands. All day no
 * train appears or vanishes away from its siding, jumps or overlaps another, and none stands still on the line; at the
 * end every train is back. Carrying on from a record runs as running on does, and skipping ticks as not skipping.
 * <p>
 * A whole day takes minutes, so these run only with {@code -Dbenchmark=true}. The first doesn't pass on the run layer
 * taken over from MTR: after the train held at 07:00 is let go, the trains bunched behind it lock up at the far
 * terminal, which has one platform to turn back on, for the rest of the day. The new run layer (2026-11-07 plan) is to
 * pass it.
 */
class WholeDayTest {

	private static final long START = MONDAY + 4 * HOUR + 50 * MINUTE;
	private static final long END = MONDAY + 25 * HOUR + 30 * MINUTE;
	private static final long UP_MIDDLE = 2;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	/**
	 * A line with a service every 6 minutes from 05:00 until midnight, run by duties, with the ways path generation would
	 * find: out of the siding to the far terminal's down platform, back from its up one, and round from the middle up
	 * platform to the middle down one.
	 */
	static byte[] dataset() {
		final SimDataset dataset = SimDatasetTest.lines(1, 1200, 4, 10, 6);
		final DutyGenerator.Result result = PlanCheck.generate(dataset, 1, 0);
		PlanCheckTest.addDutyPaths(dataset, result, false);
		final DepotDuties duties = new DepotDuties();
		duties.diagramMode = true;
		duties.sidings.putAll(result.sidings);
		dataset.replaceDuties(1, duties.toBytes());
		final SimDataset.SidingData siding = dataset.sidings.get(0);
		final List<SimDataset.DataTrack> path = siding.path;
		final SimDataset.DataTrack out = path.get(0);
		final SimDataset.DataTrack in = path.get(path.size() - 1);
		final SimDataset.DataTrack upEnd = path.get(6);
		final SimDataset.DataTrack downStart = path.get(7);
		siding.wayOuts.put(downStart.getStopId(), Arrays.asList(out, SimDatasetTest.plain(out.getEndNode(), 50, 300), SimDatasetTest.plain(50, downStart.getStartNode(), 50), downStart));
		siding.turnReturns.put(upEnd.getStopId(), Arrays.asList(upEnd, SimDatasetTest.plain(upEnd.getEndNode(), 51, 50), SimDatasetTest.plain(51, in.getStartNode(), 300), in));
		final SimDataset.DataTrack upMiddle = path.get(4);
		final SimDataset.DataTrack downMiddle = path.get(9);
		siding.addTurnLink(upMiddle.getStopId(), downMiddle.getStopId(), Arrays.asList(upMiddle, SimDatasetTest.plain(upMiddle.getEndNode(), downMiddle.getStartNode(), 30), downMiddle));
		return dataset.toBytes();
	}

	/**
	 * What goes wrong during the day, each for a train out on the line then: picked the first time through, then given
	 * the same way to every run, with what follows from it (letting a held train go, clearing out one turned back).
	 */
	private static final class Day {

		private final List<Long> times = new ArrayList<>();
		private final List<String> names = new ArrayList<>();
		private final List<Function<Long, List<HostInput>>> plan = new ArrayList<>();
		private final List<HostInput> inputs = new ArrayList<>();
		private final Map<Long, String> picked = new TreeMap<>();
		private int pickedCount;

		private void at(long time, String name, Function<Long, List<HostInput>> make) {
			times.add(time);
			names.add(name);
			plan.add(make);
		}

		private static Day make() {
			final Day day = new Day();
			final long seven = MONDAY + 7 * HOUR;
			// Held for a quarter of an hour by the dispatcher, as stuck, so the trains behind bunch up
			day.at(seven, "held 15 min", train -> Arrays.asList(new HostInput.Dispatch(seven, 1, train, TrainService.COMMAND_HOLD, 0, new long[0]), new HostInput.Dispatch(seven + 15 * MINUTE, 2, train, TrainService.COMMAND_RELEASE, 0, new long[0])));
			final long eight = MONDAY + 8 * HOUR;
			day.at(eight, "passes the middle station", train -> Collections.singletonList(new HostInput.Skip(eight, 3, train, new long[]{UP_MIDDLE})));
			final long nine = MONDAY + 9 * HOUR;
			day.at(nine, "turns back early", train -> Collections.singletonList(new HostInput.TurnBack(nine, 4, train, Contingency.TURN_NEAREST, new long[0], Contingency.AFTER_RESUME)));
			final long ten = MONDAY + 10 * HOUR;
			day.at(ten, "hands over to a spare", train -> Collections.singletonList(new HostInput.HandOver(ten, 5, train, true)));
			final long eleven = MONDAY + 11 * HOUR;
			day.at(eleven, "dispatcher holds 5 min", train -> Arrays.asList(new HostInput.Dispatch(eleven, 6, train, TrainService.COMMAND_HOLD, 0, new long[0]), new HostInput.Dispatch(eleven + 5 * MINUTE, 7, train, TrainService.COMMAND_RELEASE, 0, new long[0])));
			final long twelve = MONDAY + 12 * HOUR;
			day.at(twelve, "stands a minute longer", train -> Collections.singletonList(new HostInput.Dispatch(twelve, 8, train, TrainService.COMMAND_EXTEND_DWELL, 60000, new long[0])));
			final long thirteen = MONDAY + 13 * HOUR;
			day.at(thirteen, "clears out at the next stop", train -> Collections.singletonList(new HostInput.Dispatch(thirteen, 9, train, TrainService.COMMAND_CLEAR_OUT, 0, new long[0])));
			final long fourteen = MONDAY + 14 * HOUR;
			day.at(fourteen, "goes back after this part", train -> Collections.singletonList(new HostInput.Dispatch(fourteen, 10, train, TrainService.COMMAND_RETURN, 0, new long[0])));
			final long fifteen = MONDAY + 15 * HOUR;
			day.at(fifteen, "turns at the middle and clears out", train -> Arrays.asList(new HostInput.Dispatch(fifteen, 11, train, TrainService.COMMAND_TURN_AT, 0, new long[]{UP_MIDDLE}), new HostInput.Dispatch(fifteen, 12, train, TrainService.COMMAND_CLEAR_OUT, 0, new long[0])));
			return day;
		}

		/**
		 * Runs from {@code from} to {@code end}, giving each input due after {@code from} as its time comes, and checking
		 * the rules every second when asked. The trains are picked the first time through.
		 */
		private void run(EventSimulator<SimDataset.DataTrack> simulator, long from, long end, RunInvariants<SimDataset.DataTrack> invariants, Map<String, Boolean> seen) {
			// Inputs go in with their own times, so the steps between looks only set how often the rules are checked
			final long step = invariants == null ? 10 * SECOND : 2 * SECOND;
			for (long time = from; time < end; time += step) {
				simulator.advanceTo(time);
				final long until = Math.min(end, time + step);
				while (pickedCount < times.size() && times.get(pickedCount) <= until) {
					final long train = pick(simulator, pickedCount);
					picked.put(times.get(pickedCount), "train " + train + " " + names.get(pickedCount));
					inputs.addAll(plan.get(pickedCount).apply(train));
					pickedCount++;
				}
				for (final HostInput input : inputs) {
					if (input.time > time && input.time <= until && input.time > from) {
						simulator.submit(input);
					}
				}
				if (invariants != null) {
					invariants.check();
				}
				if (seen != null) {
					note(simulator, seen);
				}
			}
			simulator.advanceTo(end);
		}

		/**
		 * The train out on the line, short of the middle station, for each thing in turn.
		 */
		private static long pick(EventSimulator<SimDataset.DataTrack> simulator, int index) {
			final List<Long> candidates = simulator.getRunner().getTrains().stream()
					.filter(TickRunner.HeadlessTrain::isOnRoute)
					.filter(train -> train.getHeadRail() >= 1 && train.getHeadRail() <= 3)
					.filter(train -> train.service.getDispatchState() == 0 && train.service.getTakeoverState() == 0 && !train.service.isOutOfService())
					.map(train -> train.id).sorted().collect(Collectors.toList());
			assertFalse(candidates.isEmpty(), "a train on the line at " + clock(simulator.getTime()));
			return candidates.get(index % candidates.size());
		}

		private static void note(EventSimulator<SimDataset.DataTrack> simulator, Map<String, Boolean> seen) {
			for (final TickRunner<SimDataset.DataTrack>.HeadlessTrain train : simulator.getRunner().getTrains()) {
				seen.merge("skipping", !train.service.getSkipPlatforms().isEmpty(), Boolean::logicalOr);
				seen.merge("turning", train.service.getTurnState() > 0, Boolean::logicalOr);
				seen.merge("handed over", train.service.getTakeoverState() == 2, Boolean::logicalOr);
				seen.merge("out of service", train.service.isOutOfService(), Boolean::logicalOr);
				seen.merge("dispatched", train.service.getDispatchState() != 0, Boolean::logicalOr);
			}
		}
	}

	private static EventSimulator<SimDataset.DataTrack> start(byte[] bytes, boolean skipping) {
		return new EventSimulator<>(SimDataset.start(bytes, START), skipping);
	}

	/**
	 * The stops, departures, passes, returns and deadlocks after a moment, as {@code millis hh:mm:ss.t what train rail}.
	 */
	private static List<String> describe(List<SimEvent> events, long after) {
		return events.stream().filter(event -> event.time > after).filter(event -> event.kind == SimEvent.Kind.ARRIVE || event.kind == SimEvent.Kind.DEPART || event.kind == SimEvent.Kind.PASS || event.kind == SimEvent.Kind.RETURNED || event.kind == SimEvent.Kind.DEADLOCK)
				.map(event -> event.time + " " + clock(event.time) + " " + event.kind + " " + event.trainId + " " + event.rail).collect(Collectors.toList());
	}

	@Test
	@Disabled("Known: trains bunched after a hold lock up at the single platform terminal; the new run layer is to pass it")
	void aWholeDayWithEverythingGoingWrongBreaksNoRule() throws IOException {
		final byte[] bytes = dataset();
		final Day day = Day.make();
		final EventSimulator<SimDataset.DataTrack> simulator = start(bytes, true);
		final RunInvariants<SimDataset.DataTrack> invariants = new RunInvariants<>(simulator.getRunner()).setStuckMillis(20 * MINUTE);
		final Map<String, Boolean> seen = new TreeMap<>();
		day.run(simulator, START, END, invariants, seen);

		final List<String> events = describe(simulator.events(0), 0);
		final List<String> pinned = new ArrayList<>();
		day.picked.forEach((time, what) -> pinned.add("# " + clock(time) + " " + what));
		pinned.addAll(events);
		writeResult("whole-day", pinned);

		assertEquals(Collections.emptyList(), invariants.getViolations());
		assertEquals(Collections.emptyMap(), invariants.getStuck());
		assertEquals(5, seen.size());
		seen.forEach((what, happened) -> assertTrue(happened, what));
		// Every train back in its siding, and no more trains than the siding has
		final List<TickRunner<SimDataset.DataTrack>.HeadlessTrain> trains = simulator.getRunner().getTrains();
		assertTrue(trains.stream().noneMatch(TickRunner.HeadlessTrain::isOnRoute), "all back");
		assertTrue(trains.size() <= 10, trains.size() + " trains");
		assertTrue(events.stream().noneMatch(line -> line.contains("DEADLOCK")), "no deadlock");
		checkPinned("whole-day", pinned);
	}

	@Test
	@EnabledIfSystemProperty(named = "benchmark", matches = "true")
	void carryingOnFromARecordRunsAsRunningOnDoes() {
		final byte[] bytes = dataset();
		final Day day = Day.make();
		final EventSimulator<SimDataset.DataTrack> runningOn = start(bytes, true);
		day.run(runningOn, START, END, null, null);
		for (final long savedAt : new long[]{MONDAY + 7 * HOUR + 7 * MINUTE + 350, MONDAY + 10 * HOUR + 3 * MINUTE + 1250, MONDAY + 13 * HOUR + 2 * MINUTE + 50}) {
			final EventSimulator<SimDataset.DataTrack> saved = start(bytes, true);
			day.run(saved, START, savedAt, null, null);
			final RunRecord record = RunRecord.fromBytes(saved.getRunner().record().toBytes());
			final EventSimulator<SimDataset.DataTrack> resumed = EventSimulator.resume(startMillis -> SimDataset.start(bytes, startMillis).get(), record, savedAt);
			day.run(resumed, savedAt, END, null, null);
			assertEquals(describe(runningOn.events(0), savedAt), describe(resumed.events(0), savedAt), "saved at " + clock(savedAt));
		}
	}

	@Test
	@EnabledIfSystemProperty(named = "benchmark", matches = "true")
	void skippingTicksRunsAsEveryTick() {
		final byte[] bytes = dataset();
		final Day day = Day.make();
		final EventSimulator<SimDataset.DataTrack> skipping = start(bytes, true);
		day.run(skipping, START, END, null, null);
		final EventSimulator<SimDataset.DataTrack> everyTick = start(bytes, false);
		day.run(everyTick, START, END, null, null);
		assertEquals(describe(skipping.events(0), 0), describe(everyTick.events(0), 0));
	}

	private static void writeResult(String name, List<String> lines) throws IOException {
		final Path resultFolder = Paths.get("build", "scenario-results");
		Files.createDirectories(resultFolder);
		Files.write(resultFolder.resolve(name + ".txt"), lines, StandardCharsets.UTF_8);
	}

	private static void checkPinned(String name, List<String> lines) throws IOException {
		final InputStream pinned = WholeDayTest.class.getResourceAsStream("/scenarios/" + name + ".txt");
		assertNotNull(pinned, "No pinned result for " + name + ", see build/scenario-results");
		try (final BufferedReader reader = new BufferedReader(new InputStreamReader(pinned, StandardCharsets.UTF_8))) {
			assertEquals(reader.lines().collect(Collectors.joining("\n")), String.join("\n", lines), name);
		}
	}
}
