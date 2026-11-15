package transitsim.sim;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import transitsim.plan.OperatingDay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static transitsim.sim.ScenarioLine.*;

/**
 * Running on past one operating day (T04, T05): the duty-run line of {@link WholeDayTest} for two whole days without
 * anything going wrong. The second day runs exactly as the first, 24 hours later; between them, after the last service
 * and before the first of the next day, every train is back in its siding and no train holds a reservation left over
 * from the first day. No rule is broken and nothing is stuck over both days.
 * <p>
 * Once in UTC with the day starting at midnight, and once at +08:00 with the day starting at 04:00, as a Chinese world
 * has it: there the services, local 05:00 until midnight, are saved as UTC 21:00 until 16:00, so UTC midnight falls in
 * the middle of the day's services.
 * <p>
 * Which train runs a duty may change from one day to the next: the siding keeps its spare trains in its shed and sends
 * out the one kept longest first (P2-C), so on the first day trains 1 and 2 run while 3 waits, and on the second 1 and
 * 3. The days are compared with the trains named in the order they first move each day, and the siding has to keep the
 * same trains, none made or lost. Two days take minutes, so these run only with {@code -Dbenchmark=true}.
 */
class TwoDaysTest {

	private static final long DAY = OperatingDay.MILLIS_PER_DAY;
	/**
	 * Ten minutes before the first service, local time.
	 */
	private static final long LOCAL_START = 4 * HOUR + 50 * MINUTE;
	/**
	 * How often the rules are checked, as in {@link WholeDayTest}: a train going into its siding passes the last rail
	 * before it and the siding's way in within a few seconds, and the rules have to see it on the way in.
	 */
	private static final long STEP = 2 * SECOND;
	/**
	 * The one siding of the line, as {@link SimDatasetTest#lines} makes it.
	 */
	private static final long SIDING = 100000;

	@AfterEach
	void resetZone() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	@EnabledIfSystemProperty(named = "benchmark", matches = "true")
	void twoDaysInUtc() throws IOException {
		OperatingDay.set("UTC", 0);
		runTwoDays("two-days-utc", 0, MONDAY + LOCAL_START);
	}

	@Test
	@EnabledIfSystemProperty(named = "benchmark", matches = "true")
	void twoDaysWithUtcMidnightInTheMiddleOfTheServices() throws IOException {
		OperatingDay.set("+08:00", 4);
		final long shift = -8 * HOUR;
		// Local 04:50 on Tuesday is 20:50 UTC on Monday
		runTwoDays("two-days-plus-8", shift, MONDAY + DAY + LOCAL_START + shift);
	}

	/**
	 * @param shift added to every service time of the line, as saved in UTC
	 * @param start ten minutes before the first service of the first day
	 */
	private static void runTwoDays(String name, long shift, long start) throws IOException {
		final byte[] bytes = WholeDayTest.dataset(shift);
		final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(SimDataset.start(bytes, start), true);
		final TickRunner<SimDataset.DataTrack> runner = simulator.getRunner();
		final RunInvariants<SimDataset.DataTrack> invariants = new RunInvariants<>(runner).setStuckMillis(20 * MINUTE);

		final long between = start + DAY;
		run(simulator, invariants, start, between);
		// Between the days: everyone back in the siding and nothing held from the first day
		assertFalse(runner.getTrains().isEmpty(), "a train in the siding");
		assertEquals(Collections.emptyList(), onTheLine(runner), "all back at " + clock(between));
		assertEquals(Collections.emptySet(), runner.getReservations().getHolders(), "reservations held at " + clock(between));
		final Set<Long> fleetBetween = fleet(runner);
		run(simulator, invariants, between, between + DAY);

		final List<SimEvent> events = simulator.events(0);
		final List<String> first = describe(events, start, between, 0);
		final List<String> second = describe(events, between, between + DAY, DAY);
		final Path resultFolder = Paths.get("build", "scenario-results");
		Files.createDirectories(resultFolder);
		Files.write(resultFolder.resolve(name + "-1.txt"), first, StandardCharsets.UTF_8);
		Files.write(resultFolder.resolve(name + "-2.txt"), second, StandardCharsets.UTF_8);

		assertTrue(first.size() > 1000, first.size() + " events on the first day");
		assertTrue(first.stream().anyMatch(line -> line.contains(" RETURNED ")), "trains returned on the first day");
		assertTrue(first.stream().noneMatch(line -> line.contains(" DEADLOCK ")) && second.stream().noneMatch(line -> line.contains(" DEADLOCK ")), "no deadlock");
		assertEquals(first, second, "the second day as the first, 24 h later");
		assertEquals(Collections.emptyList(), invariants.getViolations());
		assertEquals(Collections.emptyMap(), invariants.getStuck());
		assertEquals(Collections.emptyList(), onTheLine(runner), "all back after the second day");
		assertEquals(Collections.emptySet(), runner.getReservations().getHolders(), "reservations held after the second day");
		assertEquals(fleetBetween, fleet(runner), "the same trains in the siding and its shed after each day");
	}

	private static void run(EventSimulator<SimDataset.DataTrack> simulator, RunInvariants<SimDataset.DataTrack> invariants, long from, long to) {
		for (long time = from + STEP; time <= to; time += STEP) {
			simulator.advanceTo(time);
			invariants.check();
		}
	}

	private static List<String> onTheLine(TickRunner<SimDataset.DataTrack> runner) {
		return runner.getTrains().stream().filter(TickRunner.HeadlessTrain::isOnRoute).map(train -> train.id + " at " + train.getHeadRail()).collect(Collectors.toList());
	}

	/**
	 * The trains of the siding, standing in it or kept in its shed.
	 */
	private static Set<Long> fleet(TickRunner<SimDataset.DataTrack> runner) {
		final Set<Long> trains = runner.getTrains().stream().map(train -> train.id).collect(Collectors.toCollection(TreeSet::new));
		trains.addAll(runner.getShed(SIDING));
		return trains;
	}

	/**
	 * The stops, departures, passes, returns and deadlocks in {@code (from, to]}, as in {@link WholeDayTest}, with
	 * {@code back} taken off each time and the trains named T1, T2 and so on in the order they first appear, so the days
	 * can be compared.
	 */
	private static List<String> describe(List<SimEvent> events, long from, long to, long back) {
		final Map<Long, String> names = new HashMap<>();
		return events.stream().filter(event -> event.time > from && event.time <= to)
				.filter(event -> event.kind == SimEvent.Kind.ARRIVE || event.kind == SimEvent.Kind.DEPART || event.kind == SimEvent.Kind.PASS || event.kind == SimEvent.Kind.RETURNED || event.kind == SimEvent.Kind.DEADLOCK)
				.map(event -> (event.time - back) + " " + clock(event.time - back) + " " + event.kind + " " + names.computeIfAbsent(event.trainId, id -> "T" + (names.size() + 1)) + " " + event.rail).collect(Collectors.toList());
	}
}
