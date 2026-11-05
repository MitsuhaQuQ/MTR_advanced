package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.io.BinaryKeyed;
import transitsim.io.ByteArrayBinary;
import transitsim.plan.DepotDuties;
import transitsim.plan.DutyPlan;
import transitsim.plan.DutyShapes;
import transitsim.plan.OperatingDay;
import transitsim.plan.PlanRoute;
import transitsim.plan.TripPath;
import transitsim.planning.DutyGenerator;
import transitsim.planning.PlanCheck;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Duties the planner works out, run by the kernel (S6), on lines like {@link SimDatasetTest#lines}: a depot sends trains
 * up the line, they turn back at the far end and come down, and go back to the depot.
 */
class PlanCheckTest {

	private static final long DAY_START = ScenarioLine.MONDAY;
	private static final long DEPOT = 1;
	private static final long UP = 1;
	private static final long DOWN = 2;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void generatedDutiesRunAsPlanned() {
		final SimDataset dataset = SimDatasetTest.lines(1, 400, 2, 4, 10);
		final DutyGenerator.Result result = PlanCheck.generate(dataset, DEPOT, 0);
		assertEquals(0, result.uncovered);
		assertTrue(result.trains > 0 && result.trains <= 4, String.valueOf(result.trains));

		addDutyPaths(dataset, result, false);
		final PlanCheck check = PlanCheck.check(dataset, DEPOT, result.sidings, DAY_START);
		assertTrue(check.passes(), describe(check));
		// Every ten minutes from 06:00 until 08:00 each way
		assertEquals(24, check.dayRun.trips.size());
	}

	@Test
	void servicesThePlannerCantCoverDontRun() {
		// One train for a service every ten minutes each way on a long line
		final SimDataset dataset = SimDatasetTest.lines(1, 2400, 2, 1, 10);
		final DutyGenerator.Result result = PlanCheck.generate(dataset, DEPOT, 0);
		assertTrue(result.uncovered > 0);

		addDutyPaths(dataset, result, false);
		final PlanCheck check = PlanCheck.check(dataset, DEPOT, result.sidings, DAY_START);
		assertFalse(check.passes());
		// The planner counts a week of trips, the kernel runs one day of services that run every day
		assertEquals(result.uncovered, 7 * check.notRun, describe(check));
		assertEquals(0, check.deadlocks, describe(check));
	}

	@Test
	void dutiesOnATurnBackTheRailsDontHaveDontRun() {
		// The planner takes the last platform down and the first one up for the same station, and turns trains back between
		// them, but no track joins them, so the depot finds no path for those duties
		final SimDataset dataset = SimDatasetTest.lines(1, 400, 2, 4, 10);
		final Map<Long, PlanRoute> routes = dataset.getPlanRoutes();
		final Map<Long, long[][]> fastestTimes = dataset.getFastestTimes(DEPOT);
		final List<DutyGenerator.ServiceInput> services = DutyGenerator.servicesOf(java.util.Arrays.asList(routes.get(UP), routes.get(DOWN)), route -> fastestTimes.get(route.getRouteId()), 0, route -> {
		});
		final Map<Long, Long> stationOf = new HashMap<>();
		stationOf.put(1L, 9L);
		stationOf.put(4L, 9L);
		final DutyGenerator.Result result = DutyGenerator.generate(services, Collections.singletonList(new DutyGenerator.SidingInput(100000, 4)), stationOf, 30 * 60000);
		assertEquals(0, result.uncovered, String.join(", ", result.issues));
		assertTrue(result.sidings.values().stream().flatMap(List::stream).anyMatch(slot -> slot.getDuty(0) != null && slot.getDuty(0).segments.size() > 2));

		addDutyPaths(dataset, result, true);
		final PlanCheck check = PlanCheck.check(dataset, DEPOT, result.sidings, DAY_START);
		assertFalse(check.passes(), describe(check));
		assertTrue(check.notRun > 0, describe(check));
	}

	/**
	 * The line of {@link SimDatasetTest#lines} with the up route listed by depot 1 and the down route by depot 2, each with a
	 * siding of the same path.
	 */
	private static SimDataset splitLine(String upLine, String downLine) {
		final SimDataset dataset = SimDatasetTest.lines(1, 400, 2, 4, 10);
		final SimDataset.DepotData depot = dataset.depots.get(0);
		dataset.depots.set(0, new SimDataset.DepotData(DEPOT, new long[]{UP}, false, new byte[0]));
		dataset.depots.add(new SimDataset.DepotData(2, new long[]{DOWN}, depot.cycleRoutes, new byte[0]));
		final SimDataset.SidingData siding = dataset.sidings.get(0);
		dataset.sidings.add(new SimDataset.SidingData(100001, 2, siding.routeIds, siding.trainCars, siding.spacing, siding.railLength, siding.acceleration, siding.deceleration, siding.maxTrains, siding.usesSpeedCurve, siding.minSpeed, siding.repeatIndex1, siding.repeatIndex2, siding.loopJoinIndex, siding.path, siding.turnBacks));
		dataset.routes.forEach(route -> route.lineKey = route.id == UP ? upLine : downLine);
		return SimDataset.fromBytes(dataset.toBytes());
	}

	private static Set<Long> plannedRoutes(DutyGenerator.Result result) {
		final Set<Long> routeIds = new TreeSet<>();
		result.sidings.values().forEach(slots -> slots.forEach(slot -> slot.days.forEach(day -> day.segments.forEach(segment -> routeIds.add(segment.routeId)))));
		return routeIds;
	}

	@Test
	void depotsOfTheSameLinePlanBothWaysTogether() {
		final SimDataset dataset = splitLine("train|Line", "train|Line");
		// On its own the depot only plans the way it lists
		assertEquals(Collections.singleton(UP), plannedRoutes(PlanCheck.generate(dataset, DEPOT, 0)));

		final DutyGenerator.Result result = PlanCheck.generateByLine(dataset, DEPOT, 0);
		assertEquals(0, result.uncovered, String.join(", ", result.issues));
		assertEquals(new TreeSet<>(java.util.Arrays.asList(UP, DOWN)), plannedRoutes(result));
		addDutyPaths(dataset, result, false);
		final PlanCheck check = PlanCheck.checkByLine(dataset, DEPOT, result.sidings, DAY_START);
		assertTrue(check.passes(), describe(check));
		assertEquals(24, check.dayRun.trips.size());
	}

	@Test
	void depotsOfOtherLinesArentPlannedTogether() {
		final SimDataset dataset = splitLine("train|Up", "train|Down");
		final DutyGenerator.Result result = PlanCheck.generateByLine(dataset, DEPOT, 0);
		assertEquals(Collections.singleton(UP), plannedRoutes(result));
		assertTrue(result.sidings.keySet().stream().allMatch(sidingId -> sidingId == 100000));
	}

	@Test
	void theSameInputGivesTheSameDuties() {
		final DutyGenerator.Result first = PlanCheck.generate(SimDatasetTest.lines(1, 400, 2, 4, 10), DEPOT, 0);
		final DutyGenerator.Result second = PlanCheck.generate(SimDatasetTest.lines(1, 400, 2, 4, 10), DEPOT, 0);
		assertArrayEquals(toDuties(first).toBytes(), toDuties(second).toBytes());
	}

	private static DepotDuties toDuties(DutyGenerator.Result result) {
		final DepotDuties duties = new DepotDuties();
		duties.diagramMode = true;
		duties.sidings.putAll(result.sidings);
		return duties;
	}

	/**
	 * The paths of the duties' shapes, as the game's depot would find them along the rails: on these lines a duty of an up
	 * service and the down service after it runs the siding's own path. Stops are counted again as the depot does.
	 */
	/**
	 * @param skipOthers leave out duties of other shapes, as the depot does when it finds no path for them
	 */
	static void addDutyPaths(SimDataset dataset, DutyGenerator.Result result, boolean skipOthers) {
		final Map<Long, PlanRoute> routes = dataset.getPlanRoutes();
		result.sidings.forEach((sidingId, slots) -> {
			final SimDataset.SidingData siding = dataset.sidings.stream().filter(eachSiding -> eachSiding.id == sidingId).findFirst().orElseThrow(AssertionError::new);
			final Set<String> keys = new HashSet<>();
			for (int i = 0; i < slots.size(); i++) {
				for (int weekday = 0; weekday < 7; weekday++) {
					final String key = DutyShapes.getSlotKey(slots, i, weekday, routes::get);
					if (key == null || !keys.add(key)) {
						continue;
					}
					final List<Long> shape = slots.get(i).getDuty(weekday).segments.stream().map(segment -> segment.routeId).collect(Collectors.toList());
					final boolean known = shape.equals(java.util.Arrays.asList(UP)) || shape.equals(java.util.Arrays.asList(DOWN)) || shape.equals(java.util.Arrays.asList(UP, DOWN));
					assertTrue(known || skipOthers, key);
					if (known) {
						siding.tripPaths.add(dutyPath(siding.path, key, shape));
					}
				}
			}
		});
	}

	/**
	 * The siding's path is out to the first platform (rails 0 and 1), the up route (2 to 6), turning back at the far end onto
	 * the down route (7 to 11), and back to the siding (12 and 13). A duty of one service runs the rest of the way without
	 * stopping, as the way between a platform and the siding has no stops.
	 */
	private static SimDataset.TripPathData dutyPath(List<SimDataset.DataTrack> sidingPath, String key, List<Long> shape) {
		final int bodyStart = shape.get(0) == UP ? 2 : 7;
		final int bodyEnd = shape.get(shape.size() - 1) == DOWN ? 11 : 6;
		final List<SimDataset.DataTrack> path = new ArrayList<>();
		int stopIndex = 1;
		for (int i = 0; i < sidingPath.size(); i++) {
			final SimDataset.DataTrack track = sidingPath.get(i);
			final boolean stops = i >= bodyStart && i <= bodyEnd;
			if (i > 0 && track.isPlatformTrack() && track.getStopId() != 0 && !sidingPath.get(i - 1).isPlatformTrack()) {
				stopIndex++;
			}
			path.add(new SimDataset.DataTrack(track.getStartNode(), track.getEndNode(), track.getTrackLength(), track.isPlatformTrack(), track.isSidingTrack(), track.getStopId(), stops || i == 0 || i == sidingPath.size() - 1 ? track.getDwellTime() : 0, i == 0 ? 0 : stopIndex, track.getMaxSpeed(), track.canAccelerate(), track.hasSignal(), track.isTwoWay()));
		}
		final int[] segmentStarts = shape.size() == 2 ? new int[]{0, 5} : new int[]{0};
		final DutyPlan<SimDataset.DataTrack, Object> dutyPlan = new DutyPlan<>(key, shape, new HashMap<>(), new HashMap<>(), new HashMap<>(), path.subList(bodyStart, bodyEnd + 1), segmentStarts, null, null, false, false);
		final int[] dutyStarts = new int[segmentStarts.length];
		for (int i = 0; i < dutyStarts.length; i++) {
			dutyStarts[i] = segmentStarts[i] + bodyStart;
		}
		final TripPath<SimDataset.DataTrack> tripPath = new TripPath<>(dutyPlan, dutyStarts, path);
		final BinaryKeyed.Writer values = new BinaryKeyed.Writer();
		try {
			tripPath.write(values);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
		values.writeTo(writer);
		return new SimDataset.TripPathData(writer.toBytes(), path);
	}

	private static long[][] scale(long[][] times, int factor) {
		final long[][] scaled = new long[times.length][];
		for (int i = 0; i < times.length; i++) {
			scaled[i] = new long[]{times[i][0] / factor, times[i][1] / factor};
		}
		return scaled;
	}

	private static String describe(PlanCheck check) {
		return check.dayRun.problems.stream().map(problem -> ScenarioLine.clock(problem.time) + " " + problem.kind + " " + problem.routeId + "@" + ScenarioLine.clock(problem.serviceMillis) + " " + problem.durationMillis / 1000 + "s").collect(Collectors.joining(", "));
	}
}
