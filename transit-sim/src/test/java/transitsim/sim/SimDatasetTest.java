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
	 * The benchmark of contracts document 8.1, which takes a while, so it only runs when asked for:
	 * {@code ./gradlew -p transit-sim test -Dbenchmark=true --tests '*SimDatasetTest*'}.
	 */
	@Test
	@EnabledIfSystemProperty(named = "benchmark", matches = "true")
	void aBigNetworkRunsADay() {
		// 25 long lines with a service every 3 minutes each way from 05:00 until midnight
		final byte[] bytes = lines(25, 2400, 6, 12, 3).toBytes();
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
		System.out.printf("Big network: %d trains, %d departures, built in %d ms, a day simulated in %d ms, %.1f%% of ticks skipped%n", trains.size(), departures, (runStart - buildStart) / 1000000, (runEnd - runStart) / 1000000, skipped);
		assertTrue(trains.size() >= 100, String.valueOf(trains.size()));
		assertTrue(simulator.events(0).stream().noneMatch(event -> event.kind == SimEvent.Kind.DEADLOCK));
	}

	/**
	 * @param count     how many lines like {@link ScenarioLine}, each with its own nodes, routes and depot
	 * @param headway   the minutes between services
	 */
	private static SimDataset lines(int count, double between, int trainCars, int maxTrains, int headway) {
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

	private static TestRoute route(long id, int firstMinute, int headway, long... platformIds) {
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

	private static SimDataset.DataTrack plain(long from, long to, double length) {
		return new SimDataset.DataTrack(from, to, length, false, false, 0, 0, 0, 1, true, true, false);
	}

	private static SimDataset.DataTrack platform(long from, long to, long platformId, boolean twoWay) {
		return new SimDataset.DataTrack(from, to, 30, true, false, platformId, 20, 0, 1, false, true, twoWay);
	}

	private static SimDataset.DataTrack siding(long from, long to, long sidingId, double length) {
		return new SimDataset.DataTrack(from, to, length, false, true, sidingId, 0, 0, 0.5F, false, false, true);
	}
}
