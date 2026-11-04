package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import transitsim.motion.MotionEvent;
import transitsim.plan.OperatingDay;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;

/**
 * The data the game saves for the day tool, and how fast a big network's day runs from it (S4e, contracts document 8.1).
 */
class SimDatasetTest {

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void theDataReadsBackAsItWasWritten() {
		final byte[] bytes = lines(3, 400, 2, 4, 10).toBytes();
		assertArrayEquals(bytes, SimDataset.fromBytes(bytes).toBytes());

		// With the trains as they were (G0)
		final SimDataset dataset = lines(3, 400, 2, 4, 10);
		final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(SimDataset.start(bytes, MONDAY + 5 * HOUR + 50 * MINUTE));
		simulator.advanceTo(MONDAY + 7 * HOUR);
		dataset.trains = simulator.getRunner().record();
		assertTrue(dataset.trains.trains.size() > 4);
		final byte[] withTrains = dataset.toBytes();
		final SimDataset readBack = SimDataset.fromBytes(withTrains);
		assertArrayEquals(withTrains, readBack.toBytes());
		assertArrayEquals(dataset.trains.toBytes(), readBack.trains.toBytes());

		// With signal blocks (G1)
		final SimDataset blocks = SignalBlockTest.line(true);
		final SimDataset blocksBack = SimDataset.fromBytes(blocks.toBytes());
		assertEquals(blocks.signalBlocks, blocksBack.signalBlocks);
		assertArrayEquals(blocks.toBytes(), blocksBack.toBytes());
	}

	@Test
	void routesKeepTheFastestTimesTheGameWorkedOut() {
		final SimDataset dataset = lines(1, 400, 2, 4, 10);
		final long[][] times = {{0, 0}, {30000, 50000}, {90000, 90000}};
		dataset.routes.get(0).fastestTimes = times;
		final SimDataset read = SimDataset.fromBytes(dataset.toBytes());
		assertArrayEquals(times, read.routes.get(0).fastestTimes);
		assertEquals(null, read.routes.get(1).fastestTimes);

		// The route's own times come first; a route without them is timed along the depot's path as before
		final Map<Long, long[][]> fastest = read.getFastestTimes(1);
		assertArrayEquals(times, fastest.get(read.routes.get(0).id));
		assertTrue(fastest.containsKey(read.routes.get(1).id));

		// A draft of the timetable with the same stops keeps them
		final SimDataset.RouteData draft = SimDataset.RouteData.fromBytes(read.routes.get(0).toBytes());
		assertEquals(null, draft.fastestTimes);
		assertTrue(read.replaceRoute(draft));
		assertArrayEquals(times, read.getFastestTimes(1).get(draft.id));
	}

	@Test
	void dataOfTheFirstVersionReadsWithoutTrains() {
		// Version 1 had nothing after the sidings, where version 3 says in a byte each that there are no trains and no
		// signal blocks
		final byte[] bytes = lines(3, 400, 2, 4, 10).toBytes();
		final byte[] first = Arrays.copyOf(bytes, bytes.length - 2);
		first[3] = 1;
		final SimDataset dataset = SimDataset.fromBytes(first);
		assertEquals(null, dataset.trains);
		assertArrayEquals(bytes, dataset.toBytes());
	}

	@Test
	void aWorldFromTheDataRunsAsTheScenarioDoes() throws Exception {
		final TickRunner<SimDataset.DataTrack> runner = SimDataset.fromBytes(lines(1, 400, 2, 4, 10).toBytes()).buildRunner(MONDAY + 5 * HOUR + 50 * MINUTE);
		runner.runUntil(MONDAY + 7 * HOUR);
		final List<String> lines = new ArrayList<>();
		for (final MotionEvent event : runner.getEvents()) {
			if (event.type == MotionEvent.Type.STOPPED || event.type == MotionEvent.Type.DEPARTED || event.type == MotionEvent.Type.RETURNED) {
				lines.add(String.format("%d %d %s %s", event.trainId, event.rail, event.type, ScenarioLine.clock(event.millis)));
			}
		}
		try (final BufferedReader reader = new BufferedReader(new InputStreamReader(SimDatasetTest.class.getResourceAsStream("/scenarios/terminal-line.txt"), StandardCharsets.UTF_8))) {
			assertEquals(reader.lines().collect(Collectors.joining("\n")), String.join("\n", lines));
		}
	}

	/**
	 * Trains left alone (S4f) end up where moving every train in every tick puts them, on lines busy enough for trains to
	 * wait behind each other and run late.
	 */
	@Test
	void leavingTrainsAloneChangesNothing() {
		for (final int maxTrains : new int[]{4, 8}) {
			final byte[] bytes = lines(3, 400, 2, maxTrains, 3).toBytes();
			final long start = MONDAY + 4 * HOUR + 50 * MINUTE;
			final EventSimulator<SimDataset.DataTrack> everyTick = new EventSimulator<>(SimDataset.start(bytes, start), false);
			final EventSimulator<SimDataset.DataTrack> skipping = new EventSimulator<>(SimDataset.start(bytes, start), true);
			for (long time = start + 7 * MINUTE; time <= start + 4 * HOUR; time += 7 * MINUTE + 1234) {
				everyTick.advanceTo(time);
				skipping.advanceTo(time);
				for (final TickRunner<SimDataset.DataTrack>.HeadlessTrain train : everyTick.getRunner().getTrains()) {
					assertEquals(everyTick.query(train.id), skipping.query(train.id), ScenarioLine.clock(time) + " train " + train.id);
				}
				assertEquals(everyTick.getRunner().getTrains().size(), skipping.getRunner().getTrains().size());
			}
			assertEquals(describe(everyTick.events(0)), describe(skipping.events(0)));
			assertTrue(skipping.getRunner().getTrainTicks() * 3 < everyTick.getRunner().getTrainTicks(), skipping.getRunner().getTrainTicks() + " " + everyTick.getRunner().getTrainTicks());
		}
	}

	private static List<String> describe(List<SimEvent> events) {
		return events.stream().map(event -> ScenarioLine.clock(event.time) + " " + event.kind + " " + event.trainId + " " + event.rail + " " + event.reason).collect(Collectors.toList());
	}

	/**
	 * The benchmark of contracts document 8.1, which takes a while, so it only runs when asked for:
	 * {@code ./gradlew -p transit-sim test -Dbenchmark=true --tests '*SimDatasetTest*'}.
	 */
	@Test
	@EnabledIfSystemProperty(named = "benchmark", matches = "true")
	void aBigNetworkRunsADay() {
		// 40 long lines with a service every 3 minutes each way from 05:00 until midnight, 200 trains in all
		final byte[] bytes = lines(40, 2400, 6, 12, 3).toBytes();
		final long start = MONDAY + 3 * HOUR;
		final long buildStart = System.nanoTime();
		final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(SimDataset.start(bytes, start));
		final long runStart = System.nanoTime();
		simulator.advanceTo(start + 24 * HOUR);
		final long runEnd = System.nanoTime();
		final Set<Long> trains = new TreeSet<>();
		simulator.events(0).forEach(event -> trains.add(event.trainId));
		final long departures = simulator.events(0).stream().filter(event -> event.kind == SimEvent.Kind.DEPART).count();
		final double skipped = 100.0 * simulator.getRunner().getSkippedTicks() / (24 * HOUR / 50);
		final double moved = 100.0 * simulator.getRunner().getTrainTicks() / (24 * HOUR / 50) / trains.size();
		System.out.printf("Big network: %d trains, %d departures, built in %d ms, a day simulated in %d ms, %.1f%% of ticks skipped, trains moved in %.1f%% of ticks%n", trains.size(), departures, (runStart - buildStart) / 1000000, (runEnd - runStart) / 1000000, skipped, moved);
		// The same day again one game tick at a time, as the game would run it
		final EventSimulator<SimDataset.DataTrack> stepped = new EventSimulator<>(SimDataset.start(bytes, start));
		final long[] stepNanos = new long[(int) (24 * HOUR / 50)];
		for (int i = 0; i < stepNanos.length; i++) {
			final long stepStart = System.nanoTime();
			stepped.advanceTo(start + (i + 1) * 50L);
			stepNanos[i] = System.nanoTime() - stepStart;
		}
		final long steppedTotal = Arrays.stream(stepNanos).sum();
		Arrays.sort(stepNanos);
		System.out.printf("One tick at a time: %d ms in all, 99th percentile %.3f ms, longest %.3f ms%n", steppedTotal / 1000000, stepNanos[stepNanos.length * 99 / 100] / 1E6, stepNanos[stepNanos.length - 1] / 1E6);
		assertEquals(simulator.events(0).size(), stepped.events(0).size());
		assertTrue(trains.size() >= 200, String.valueOf(trains.size()));
		assertTrue(simulator.events(0).stream().noneMatch(event -> event.kind == SimEvent.Kind.DEADLOCK));
	}

	/**
	 * @param count     how many lines like {@link ScenarioLine}, each with its own nodes, routes and depot
	 * @param headway   the minutes between services
	 */
	static SimDataset lines(int count, double between, int trainCars, int maxTrains, int headway) {
		final SimDataset dataset = new SimDataset();
		for (int line = 0; line < count; line++) {
			final long node = line * 1000L;
			final long platform = line * 100L;
			final TestRoute up = route(line * 10L + 1, 0, headway, platform + 1, platform + 2, platform + 3);
			final TestRoute down = route(line * 10L + 2, 5 % headway == 5 ? 5 : 1, headway, platform + 3, platform + 5, platform + 4);
			dataset.routes.add(new SimDataset.RouteData(up));
			dataset.routes.add(new SimDataset.RouteData(down));
			final long depotId = line + 1;
			dataset.depots.add(new SimDataset.DepotData(depotId, new long[]{up.id, down.id}, false, new byte[0]));
			final float sidingLength = trainCars * 10 + 10;
			final long sidingId = 100000 + line;
			final SimDataset.DataTrack terminal = platform(node + 6, node + 7, platform + 3, true);
			final List<SimDataset.DataTrack> path = Arrays.asList(
					siding(node, node + 1, sidingId, sidingLength),
					plain(node + 1, node + 2, 60),
					platform(node + 2, node + 3, platform + 1, false),
					plain(node + 3, node + 4, between),
					platform(node + 4, node + 5, platform + 2, false),
					plain(node + 5, node + 6, between),
					terminal,
					new SimDataset.DataTrack(node + 7, node + 6, 30, true, false, platform + 3, 0, 0, 1, false, true, true),
					plain(node + 6, node + 15, between),
					platform(node + 15, node + 14, platform + 5, false),
					plain(node + 14, node + 13, between),
					platform(node + 13, node + 12, platform + 4, false),
					plain(node + 12, node + 1, 60),
					new SimDataset.DataTrack(node + 1, node, sidingLength, false, true, sidingId, 0, 0, 0.5F, false, false, true)
			);
			dataset.sidings.add(new SimDataset.SidingData(sidingId, depotId, new long[]{up.id, down.id}, trainCars, 10, sidingLength, 0.01F, 0.02F, maxTrains, true, 0.5F, 0, 0, 0, path, new byte[0]));
		}
		return dataset;
	}

	static TestRoute route(long id, int firstMinute, int headway, long... platformIds) {
		final TestRoute route = new TestRoute(id, platformIds);
		route.timetable.enabled = true;
		route.timetable.marginPercent = 5;
		final long first = headway == 10 ? 6 * HOUR : 5 * HOUR;
		final long last = headway == 10 ? 8 * HOUR : 24 * HOUR;
		for (long millis = first + firstMinute * MINUTE; millis < last; millis += headway * MINUTE) {
			route.timetable.services.add((int) millis);
		}
		route.timetable.sanitize();
		return route;
	}

	static SimDataset.DataTrack plain(long from, long to, double length) {
		return new SimDataset.DataTrack(from, to, length, false, false, 0, 0, 0, 1, true, true, false);
	}

	private static SimDataset.DataTrack platform(long from, long to, long platformId, boolean twoWay) {
		return new SimDataset.DataTrack(from, to, 30, true, false, platformId, 20, 0, 1, false, true, twoWay);
	}

	private static SimDataset.DataTrack siding(long from, long to, long sidingId, double length) {
		return new SimDataset.DataTrack(from, to, length, false, true, sidingId, 0, 0, 0.5F, false, false, true);
	}
}
