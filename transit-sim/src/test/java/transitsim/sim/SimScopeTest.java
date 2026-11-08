package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.plan.OperatingDay;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The editor's simulated day only runs the routes' own part of the world (S5): their depots, depots sharing a route with
 * them, and depots whose trains pass where theirs do, and reports on the routes as the whole world would.
 */
class SimScopeTest {

	private static final long DAY_START = ScenarioLine.MONDAY + 3 * ScenarioLine.HOUR;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	private static Set<Long> depotIds(SimScope scope) {
		return scope.dataset.depots.stream().map(depot -> depot.id).collect(Collectors.toCollection(TreeSet::new));
	}

	/**
	 * A depot of its own whose siding's path runs from its own nodes onto these.
	 */
	private static void addDepot(SimDataset dataset, long depotId, long[] routeIds, long ownNode, long... nodes) {
		final List<SimDataset.DataTrack> path = new ArrayList<>();
		long from = ownNode;
		for (final long node : nodes) {
			path.add(SimDatasetTest.plain(from, node, 50));
			from = node;
		}
		dataset.depots.add(new SimDataset.DepotData(depotId, routeIds, false, new byte[0]));
		dataset.sidings.add(new SimDataset.SidingData(depotId * 1000, depotId, routeIds, 2, 10, 30, 0.01F, 0.02F, 1, true, 0.5F, 0, 0, 0, path, new byte[0]));
	}

	/**
	 * The report but for the trains' numbers, which a world gives its trains as they first appear.
	 */
	private static List<String> describe(DayRun dayRun) {
		final List<String> lines = new ArrayList<>();
		lines.add(dayRun.fromMillis + " " + dayRun.cancelled);
		dayRun.trips.forEach(trip -> {
			final StringBuilder line = new StringBuilder(trip.routeId + " " + trip.serviceMillis);
			trip.stops.forEach(stop -> line.append(String.format(" %d/%d/%s/%d/%d/%d/%d", stop.stopIndex, stop.platformId, stop.passed, stop.arrivalMillis, stop.departureMillis, stop.plannedArrivalMillis, stop.plannedDepartureMillis)));
			lines.add(line.toString());
		});
		dayRun.problems.forEach(problem -> lines.add(String.format("%s %d %d %d %d %d %d %s", problem.kind, problem.time, problem.durationMillis, problem.routeId, problem.serviceMillis, problem.stopIndex, problem.platformId, problem.detail)));
		return lines;
	}

	@Test
	void thePartKeepsTheNetworksStopPositionsInterlockingAndEarlyThreshold() {
		final SimDataset dataset = SimDatasetTest.lines(3, 400, 2, 8, 3);
		dataset.stopPositions.put(5L, transitsim.plan.StopPositions.CENTRED);
		dataset.signalBlocks.add(Collections.singletonList(new java.util.UUID(1, 2)));
		dataset.interlockBlocks.add(true);
		dataset.earlyThresholdMillis = 45000;
		// Written and read back, as the editor's simulation and the day tool run it
		final SimDataset scoped = SimDataset.fromBytes(SimScope.of(dataset, Collections.singleton(1L)).dataset.toBytes());
		assertEquals(dataset.stopPositions, scoped.stopPositions);
		assertEquals(dataset.interlockBlocks, scoped.interlockBlocks);
		assertEquals(45000, scoped.earlyThresholdMillis);
	}

	@Test
	void noRoutesKeepsTheWholeWorld() {
		final SimDataset dataset = SimDatasetTest.lines(3, 400, 2, 8, 3);
		final SimScope scope = SimScope.of(dataset, Collections.emptySet());
		assertSame(dataset, scope.dataset);
		assertEquals(3, scope.depots);
		assertEquals(3, scope.allDepots);
	}

	@Test
	void onlyTheRoutesOwnDepotsAndThoseSharingARoute() {
		final SimDataset dataset = SimDatasetTest.lines(3, 400, 2, 8, 3);
		// Depot 7 runs route 2 of line 0 as well, from nodes of its own
		addDepot(dataset, 7, new long[]{2}, 7000, 7001);
		final SimScope scope = SimScope.of(dataset, Collections.singleton(1L));
		assertEquals(new TreeSet<>(Arrays.asList(1L, 7L)), depotIds(scope));
		assertEquals(2, scope.depots);
		assertEquals(4, scope.allDepots);
		assertTrue(scope.dataset.sidings.stream().allMatch(siding -> siding.depotId == 1 || siding.depotId == 7));
		// Routes are kept, as they cost nothing until a depot runs them
		assertEquals(dataset.routes.size(), scope.dataset.routes.size());
	}

	@Test
	void sidingsPassingWhereTheRoutesTrainsGoAreKeptButNotTheirNeighbours() {
		final SimDataset dataset = SimDatasetTest.lines(3, 400, 2, 8, 3);
		// Depot 9's trains pass node 2 of line 0 and node 1002 of line 1; line 1 only meets depot 9, not line 0
		addDepot(dataset, 9, new long[0], 9000, 2, 1002);
		// Another siding of depot 9 nowhere near line 0
		dataset.sidings.add(new SimDataset.SidingData(9001, 9, new long[0], 2, 10, 30, 0.01F, 0.02F, 1, true, 0.5F, 0, 0, 0, Collections.singletonList(SimDatasetTest.plain(9100, 9101, 50)), new byte[0]));
		final SimScope scope = SimScope.of(dataset, Collections.singleton(1L));
		assertEquals(new TreeSet<>(Arrays.asList(1L, 9L)), depotIds(scope));
		assertEquals(1, scope.ownDepots);
		assertEquals(new TreeSet<>(Arrays.asList(100000L, 9000L)), scope.dataset.sidings.stream().map(siding -> siding.id).collect(Collectors.toCollection(TreeSet::new)));
	}

	@Test
	void theWayOfAServiceCountsAsWellAsTheUsualPath() {
		final SimDataset dataset = SimDatasetTest.lines(2, 400, 2, 8, 3);
		addDepot(dataset, 8, new long[0], 8000, 8001);
		// A service of depot 8, as a draft duty's path found along the rails, runs through node 3 of line 0
		dataset.sidings.get(dataset.sidings.size() - 1).tripPaths.add(new SimDataset.TripPathData(new byte[0], Collections.singletonList(SimDatasetTest.plain(8001, 3, 50))));
		assertEquals(new TreeSet<>(Arrays.asList(1L, 8L)), depotIds(SimScope.of(dataset, Collections.singleton(1L))));
	}

	@Test
	void theRoutesRunAsTheyDoInTheWholeWorld() {
		final SimDataset dataset = SimDatasetTest.lines(3, 400, 2, 8, 3);
		final Set<Long> report = new HashSet<>(Arrays.asList(11L, 12L));
		final DayRun whole = DayRun.run(SimDataset.fromBytes(dataset.toBytes()), DAY_START, report, Collections.emptyList(), () -> false);
		final SimScope scope = SimScope.of(SimDataset.fromBytes(dataset.toBytes()), report);
		assertEquals(1, scope.depots);
		final DayRun scoped = DayRun.run(scope.dataset, DAY_START, report, Collections.emptyList(), () -> false);
		assertFalse(whole.trips.isEmpty());
		assertEquals(describe(whole), describe(scoped));
	}

	@Test
	void fromTheTrainsAsTheyWereTheOthersAreLeftOut() {
		final SimDataset dataset = SimDatasetTest.lines(3, 400, 2, 8, 3);
		final long from = ScenarioLine.MONDAY + 7 * ScenarioLine.HOUR + 3 * ScenarioLine.MINUTE + 1250;
		final EventSimulator<SimDataset.DataTrack> saved = new EventSimulator<>(SimDataset.start(dataset.toBytes(), DAY_START));
		saved.advanceTo(from);
		dataset.trains = saved.getRunner().record();
		final Set<Long> report = new HashSet<>(Arrays.asList(1L, 2L));
		final DayRun whole = DayRun.run(SimDataset.fromBytes(dataset.toBytes()), DAY_START, from, report, Collections.emptyList(), () -> false);
		final DayRun scoped = DayRun.run(SimScope.of(SimDataset.fromBytes(dataset.toBytes()), report).dataset, DAY_START, from, report, Collections.emptyList(), () -> false);
		assertEquals(from, scoped.fromMillis);
		assertFalse(scoped.trips.isEmpty());
		assertEquals(describe(whole), describe(scoped));
	}
}
