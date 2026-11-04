package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.motion.MotionConstants;
import transitsim.plan.OperatingDay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;
import static transitsim.sim.ScenarioLine.SECOND;

/**
 * Depots sending trains out by frequency rather than by a timetable (G1), and sidings whose trains are driven by hand, on
 * two lines of {@link SimDatasetTest#lines} whose sidings are both in one depot, without timetables: a train leaves at
 * each departure, every three minutes from 05:00, the sidings taking turns in the order the game's depot has them.
 */
class FrequencyDeployTest {

	private static final long START = MONDAY + 4 * HOUR + 55 * MINUTE;
	private static final long TICK = MotionConstants.MILLIS_PER_TICK;
	private static final long FIRST_SIDING = 100000;
	private static final long SECOND_SIDING = 100001;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	/**
	 * @param manual whether the first siding's trains are driven by hand
	 */
	static SimDataset lines(boolean manual) {
		final SimDataset dataset = SimDatasetTest.lines(2, 2400, 2, 8, 3);
		final List<SimDataset.RouteData> routes = new ArrayList<>();
		for (final long id : new long[]{1, 2, 11, 12}) {
			final TestRoute route = id % 10 == 1 ? SimDatasetTest.route(id, 0, 3, id / 10 * 100 + 1, id / 10 * 100 + 2, id / 10 * 100 + 3) : SimDatasetTest.route(id, 1, 3, id / 10 * 100 + 3, id / 10 * 100 + 5, id / 10 * 100 + 4);
			route.timetable.enabled = false;
			routes.add(new SimDataset.RouteData(route));
		}
		dataset.routes.clear();
		dataset.routes.addAll(routes);
		// Both sidings in the first depot, taken in turn second siding first
		final SimDataset.SidingData second = dataset.sidings.get(1);
		dataset.sidings.set(1, new SimDataset.SidingData(second.id, 1, second.routeIds, second.trainCars, second.spacing, second.railLength, second.acceleration, second.deceleration, second.maxTrains, second.usesSpeedCurve, second.minSpeed, second.repeatIndex1, second.repeatIndex2, second.loopJoinIndex, second.path, second.turnBacks));
		dataset.depots.remove(1);
		final int[] departures = new int[40];
		for (int i = 0; i < departures.length; i++) {
			departures[i] = (int) (5 * HOUR + i * 3 * MINUTE);
		}
		dataset.depots.get(0).departures = departures;
		dataset.depots.get(0).sidingOrder = new long[]{SECOND_SIDING, FIRST_SIDING};
		dataset.sidings.get(0).manual = manual;
		return dataset;
	}

	@Test
	void theDataKeepsDeparturesOrderAndHandDriving() {
		final SimDataset copy = SimDataset.fromBytes(lines(true).toBytes());
		assertEquals(40, copy.depots.get(0).departures.length);
		assertEquals((int) (5 * HOUR + 3 * MINUTE), copy.depots.get(0).departures[1]);
		assertEquals(Arrays.asList(SECOND_SIDING, FIRST_SIDING), Arrays.asList(copy.depots.get(0).sidingOrder[0], copy.depots.get(0).sidingOrder[1]));
		assertTrue(copy.sidings.get(0).manual);
		assertFalse(copy.sidings.get(1).manual);
	}

	@Test
	void theRuleIsTheGames() {
		final List<Integer> departures = Arrays.asList((int) (5 * HOUR), (int) (5 * HOUR + 3 * MINUTE));
		// Due once the departure has passed and nothing left since
		assertEquals(0, DepotDispatcher.getMillisUntilDeploy(departures, MONDAY + 4 * HOUR, MONDAY + 5 * HOUR + SECOND, 1));
		// Not due again after a train left, until the next departure
		assertEquals((int) (3 * MINUTE - SECOND), DepotDispatcher.getMillisUntilDeploy(departures, MONDAY + 5 * HOUR + 1, MONDAY + 5 * HOUR + SECOND, 1));
		// Counting on through the departures, round to the next day
		assertEquals((int) (OperatingDay.MILLIS_PER_DAY - SECOND), DepotDispatcher.getMillisUntilDeploy(departures, 0, MONDAY + 5 * HOUR + SECOND, 2));
		// Across midnight, a train that left before the last departure of the day is due after it
		final List<Integer> lateDepartures = Arrays.asList((int) (6 * HOUR), (int) (23 * HOUR + 59 * MINUTE));
		assertEquals(0, DepotDispatcher.getMillisUntilDeploy(lateDepartures, MONDAY - 2 * MINUTE, MONDAY + SECOND, 1));
		assertEquals((int) (6 * HOUR - SECOND), DepotDispatcher.getMillisUntilDeploy(lateDepartures, MONDAY - 30 * SECOND, MONDAY + SECOND, 1));
		assertEquals(-1, DepotDispatcher.getMillisUntilDeploy(Collections.emptyList(), 0, MONDAY, 1));
	}

	@Test
	void aTrainLeavesAtEachDepartureTheSidingsTakingTurns() {
		final List<long[]> deployed = run(lines(false).toBytes(), true, START, MONDAY + 5 * HOUR + 31 * MINUTE, null);
		assertEquals(11, deployed.size(), describe(deployed));
		for (int i = 0; i < deployed.size(); i++) {
			final long departure = MONDAY + 5 * HOUR + i * 3 * MINUTE;
			assertTrue(deployed.get(i)[0] >= departure && deployed.get(i)[0] <= departure + TICK, describe(deployed));
			assertEquals(i % 2 == 0 ? SECOND_SIDING : FIRST_SIDING, deployed.get(i)[1], describe(deployed));
		}
	}

	@Test
	void skippingTicksGivesTheSame() {
		final byte[] bytes = lines(false).toBytes();
		final long end = MONDAY + 6 * HOUR;
		assertEquals(describe(run(bytes, false, START, end, null)), describe(run(bytes, true, START, end, null)));
	}

	@Test
	void carryingOnFromARecordSendsTheSameTrains() {
		final byte[] bytes = lines(false).toBytes();
		final long middle = MONDAY + 5 * HOUR + 16 * MINUTE;
		final long end = MONDAY + 5 * HOUR + 40 * MINUTE;
		final List<long[]> straight = run(bytes, true, START, end, null);
		final RunRecord[] record = new RunRecord[1];
		run(bytes, true, START, middle, runner -> record[0] = runner.record());
		final RunRecord copy = RunRecord.fromBytes(record[0].toBytes());
		assertFalse(copy.lastDeployedMillis.isEmpty());
		final LongFunction<TickRunner<SimDataset.DataTrack>> startAt = startMillis -> SimDataset.fromBytes(bytes).buildRunner(startMillis);
		final List<long[]> deployed = watch(EventSimulator.resume(startAt, copy, middle), middle, end);
		final List<long[]> expected = new ArrayList<>();
		straight.forEach(entry -> {
			if (entry[0] > middle) {
				expected.add(entry);
			}
		});
		assertEquals(describe(expected), describe(deployed));
	}

	@Test
	void recoveringAfterALoadPutsTheTrainsWhereThePlanHasThem() {
		recoverFromEveryMoment(lines(false).toBytes());
	}

	@Test
	void recoveringTrainsRunByATimetable() {
		recoverFromEveryMoment(SimDatasetTest.lines(2, 2400, 2, 8, 3).toBytes());
	}

	private static void recoverFromEveryMoment(byte[] bytes) {
		// Saved at moments when trains stand at stops, leave them and run between them
		final EventSimulator<SimDataset.DataTrack> before = new EventSimulator<>(SimDataset.start(bytes, START));
		final List<RunRecord> records = new ArrayList<>();
		for (long time = MONDAY + 5 * HOUR + 10 * MINUTE; time < MONDAY + 5 * HOUR + 14 * MINUTE; time += 3 * SECOND) {
			before.advanceTo(time);
			records.add(before.getRunner().record());
		}
		for (final RunRecord record : records) {
			// As after the world is loaded, when it isn't known when the trains were as they are
			final RunRecord loaded = new RunRecord(0);
			loaded.trains.addAll(record.trains);
			loaded.lastDeployedMillis.putAll(record.lastDeployedMillis);
			loaded.deployIndices.putAll(record.deployIndices);
			final long loadedAt = record.time + HOUR;
			final EventSimulator<SimDataset.DataTrack> simulator = EventSimulator.resume(startMillis -> SimDataset.fromBytes(bytes).buildRunner(startMillis), loaded, loadedAt);
			final List<long[]> deployed = watch(simulator, loadedAt, loadedAt + 4 * MINUTE);
			assertTrue(simulator.getRunner().getTrains().stream().anyMatch(TickRunner.HeadlessTrain::isOnRoute), ScenarioLine.clock(record.time));
			assertTrue(deployed.size() >= 1, ScenarioLine.clock(record.time) + " " + describe(deployed));
		}
	}

	@Test
	void trainsDrivenByHandStandInTheSiding() {
		final List<long[]> deployed = run(lines(true).toBytes(), true, START, MONDAY + 5 * HOUR + 31 * MINUTE, null);
		// Only the second siding sends trains out, at every departure it can take
		assertFalse(deployed.isEmpty());
		assertTrue(deployed.stream().allMatch(entry -> entry[1] == SECOND_SIDING), describe(deployed));
	}

	@Test
	void aTrainDrivenOutByHandRunsOnOnceGivenBack() {
		final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(SimDataset.start(lines(true).toBytes(), START));
		simulator.advanceTo(MONDAY + 5 * HOUR + 10 * MINUTE);
		final TickRunner<SimDataset.DataTrack>.HeadlessTrain standing = simulator.getRunner().getTrains().stream().filter(train -> train.getSidingId() == FIRST_SIDING).findFirst().orElseThrow(AssertionError::new);
		assertFalse(standing.isOnRoute());
		final long id = standing.id;
		// The host takes it over and a player drives it out onto the line, then lets go of it before the first platform
		simulator.submit(new HostInput.TakeOver(MONDAY + 5 * HOUR + 10 * MINUTE, 1, id));
		simulator.advanceTo(MONDAY + 5 * HOUR + 11 * MINUTE);
		simulator.submit(new HostInput.Release(MONDAY + 5 * HOUR + 11 * MINUTE, 2, id, 40, 0.5F));
		simulator.advanceTo(MONDAY + 5 * HOUR + 12 * MINUTE);
		assertTrue(simulator.getRunner().getTrain(id).isOnRoute());
		simulator.advanceTo(MONDAY + 6 * HOUR);
		final Set<Integer> arrivals = new HashSet<>();
		simulator.events(0).forEach(event -> {
			if (event.trainId == id && event.kind == SimEvent.Kind.ARRIVE) {
				arrivals.add(event.rail);
			}
		});
		// It stops at the platforms of its path and is back in its siding, standing again
		assertTrue(arrivals.size() >= 3, arrivals.toString());
		final TickRunner<SimDataset.DataTrack>.HeadlessTrain back = simulator.getRunner().getTrain(id);
		assertTrue(back == null || !back.isOnRoute());
		assertTrue(simulator.getRunner().getTrains().stream().filter(train -> train.getSidingId() == FIRST_SIDING).noneMatch(TickRunner.HeadlessTrain::isOnRoute));
	}

	@Test
	void runsHeldByTrainsElsewhereAreKeptFromTheRunnersTrains() {
		final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(SimDataset.start(lines(false).toBytes(), START));
		final List<RunReservations.Move> held = Collections.singletonList(new RunReservations.Move(SignalBlockTest.rail(6, 7), true));
		final List<RunReservations.Move> against = Collections.singletonList(new RunReservations.Move(SignalBlockTest.rail(6, 7), false));
		simulator.submit(new HostInput.ExternalRun(START + SECOND, 1, 77, held));
		simulator.advanceTo(START + MINUTE);
		final RunReservations reservations = simulator.getRunner().getReservations();
		// Held again every tick, so it is still held a minute later
		assertEquals(held, reservations.getHeld(77));
		assertFalse(reservations.canHold(5, against));
		assertTrue(reservations.canHold(5, held));
		simulator.submit(new HostInput.ExternalRun(START + MINUTE, 2, 77, Collections.emptyList()));
		simulator.advanceTo(START + 2 * MINUTE);
		assertTrue(reservations.getHeld(77).isEmpty());
		assertTrue(reservations.canHold(5, against));
	}

	private static List<long[]> run(byte[] bytes, boolean skipping, long start, long end, java.util.function.Consumer<TickRunner<SimDataset.DataTrack>> atEnd) {
		final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(SimDataset.start(bytes, start), skipping);
		final List<long[]> deployed = watch(simulator, start, end);
		if (atEnd != null) {
			atEnd.accept(simulator.getRunner());
		}
		return deployed;
	}

	/**
	 * Runs the simulator a tick at a time and writes down each train leaving its siding, seen as a train on the line that
	 * was in its siding after the tick before. Trains already on the line when it starts don't count.
	 *
	 * @return when each train left its siding and which siding, in order
	 */
	private static List<long[]> watch(EventSimulator<SimDataset.DataTrack> simulator, long start, long end) {
		final List<long[]> deployed = new ArrayList<>();
		final Set<Long> inSiding = new HashSet<>();
		for (long time = start + TICK; time <= end; time += TICK) {
			simulator.advanceTo(time);
			final long now = time;
			simulator.getRunner().getTrains().forEach(train -> {
				if (!train.isOnRoute()) {
					inSiding.add(train.id);
				} else if (inSiding.remove(train.id)) {
					deployed.add(new long[]{now, train.getSidingId()});
				}
			});
		}
		return deployed;
	}

	private static String describe(List<long[]> deployed) {
		final StringBuilder builder = new StringBuilder();
		deployed.forEach(entry -> builder.append(ScenarioLine.clock(entry[0])).append(' ').append(entry[1]).append(", "));
		return builder.toString();
	}
}
