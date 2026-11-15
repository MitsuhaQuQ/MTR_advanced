package transitsim.sim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import transitsim.io.BinaryKeyed;
import transitsim.io.ByteArrayBinary;
import transitsim.plan.DutyPlan;
import transitsim.plan.DutyShapes;
import transitsim.plan.OperatingDay;
import transitsim.plan.PlanRoute;
import transitsim.plan.TripPath;
import transitsim.plan.TurnBackPoint;
import transitsim.plan.TurnBacks;
import transitsim.planning.DutyGenerator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static transitsim.sim.ScenarioLine.HOUR;
import static transitsim.sim.ScenarioLine.MINUTE;
import static transitsim.sim.ScenarioLine.MONDAY;

/**
 * Trains of a depot running by duties turning back in front of the terminus on one of two platforms (站前折返), as the
 * user's 明珠线 at 田端 (0.84.0): the siding keeps the other way round once, and a train taking it runs a path made from its
 * duty's usual path then, exactly as it ran the whole copy kept before; skipping ticks and carrying on from a record,
 * standing on such a path, run as running on does.
 * <p>
 * The line of {@link RestoreTest#byDuties()}: up from platform 1 by 2 to 3 and down by 5 to 4. Its duties running up and
 * then down turn back at platform 3 or at platform 9 of the same station, from platform 2 to platform 5 either way.
 */
class FrontTurnBackRunTest {

	private static final long START = MONDAY + 4 * HOUR + 50 * MINUTE;
	private static final long END = START + 3 * HOUR + 30 * MINUTE;
	private static final long UP = 1;
	private static final long DOWN = 2;
	private static final int STOP_RAIL = 4;
	private static final int USUAL_LENGTH = 5;

	@BeforeEach
	void setUtc() {
		OperatingDay.set("UTC", 0);
	}

	@Test
	void aTrainTakingTheOtherWayRunsAsOnTheWholeCopyKeptBefore() {
		final SimDataset whole = dataset(true);
		final SimDataset onDemand = dataset(false);
		assertTrue(onDemand.sidings.get(0).tripPaths.size() < whole.sidings.get(0).tripPaths.size(), "no whole copies kept");
		assertEquals(1, onDemand.sidings.get(0).turnBackWays.size());

		final Set<String> wholeKeys = new HashSet<>();
		final Set<String> onDemandKeys = new HashSet<>();
		final EventSimulator<SimDataset.DataTrack> wholeRun = run(whole.toBytes(), true, wholeKeys);
		final EventSimulator<SimDataset.DataTrack> onDemandRun = run(onDemand.toBytes(), true, onDemandKeys);
		assertTrue(onDemandKeys.stream().anyMatch(key -> TripPath.getTurnBackPointWay(key) != null), "trains took the other way: " + onDemandKeys);
		assertEquals(wholeKeys, onDemandKeys);
		assertEquals(describe(wholeRun.events(0), 0), describe(onDemandRun.events(0), 0));
	}

	@Test
	void skippingTicksRunsAsEveryTick() {
		final byte[] bytes = dataset(false).toBytes();
		final Set<String> everyTickKeys = new HashSet<>();
		final Set<String> skippingKeys = new HashSet<>();
		final EventSimulator<SimDataset.DataTrack> everyTick = run(bytes, false, everyTickKeys);
		final EventSimulator<SimDataset.DataTrack> skipping = run(bytes, true, skippingKeys);
		assertTrue(skippingKeys.stream().anyMatch(key -> TripPath.getTurnBackPointWay(key) != null));
		assertEquals(describe(everyTick.events(0), 0), describe(skipping.events(0), 0));
	}

	@Test
	void carryingOnFromARecordOnTheOtherWayRunsAsRunningOnDoes() {
		final byte[] bytes = dataset(false).toBytes();
		final EventSimulator<SimDataset.DataTrack> runningOn = new EventSimulator<>(SimDataset.start(bytes, START));
		runningOn.advanceTo(END);
		int onOtherWay = 0;
		for (long time = START + 70 * MINUTE + 350; time < END - 10 * MINUTE; time += 3 * MINUTE + 1250) {
			final EventSimulator<SimDataset.DataTrack> saved = new EventSimulator<>(SimDataset.start(bytes, START));
			saved.advanceTo(time);
			final boolean standingOnOne = saved.getRunner().getTrains().stream().anyMatch(train -> TripPath.getTurnBackPointWay(train.service.getTripPathKey()) != null);
			if (!standingOnOne) {
				continue;
			}
			onOtherWay++;
			final RunRecord record = RunRecord.fromBytes(saved.getRunner().record().toBytes());
			final EventSimulator<SimDataset.DataTrack> resumed = EventSimulator.resume(startMillis -> SimDataset.start(bytes, startMillis).get(), record, time);
			// The train on the other way is carried on, on the path made again from its key
			saved.getRunner().getTrains().forEach(train -> assertTrue(resumed.getRunner().hasTrain(train.id), "train " + train.id + " on " + train.service.getTripPathKey()));
			resumed.advanceTo(END);
			assertEquals(describe(runningOn.events(0), time), describe(resumed.events(0), time), "saved at " + ScenarioLine.clock(time));
		}
		assertTrue(onOtherWay > 0, "a record was made with a train on the other way");
	}

	private static EventSimulator<SimDataset.DataTrack> run(byte[] bytes, boolean skipping, Set<String> keys) {
		final EventSimulator<SimDataset.DataTrack> simulator = new EventSimulator<>(SimDataset.start(bytes, START), skipping);
		for (long time = START; time < END; time += 20 * 1000) {
			simulator.advanceTo(time);
			simulator.getRunner().getTrains().forEach(train -> keys.add(train.service.getTripPathKey()));
		}
		simulator.advanceTo(END);
		return simulator;
	}

	private static List<String> describe(List<SimEvent> events, long after) {
		return events.stream().filter(event -> event.time > after && (event.kind == SimEvent.Kind.ARRIVE || event.kind == SimEvent.Kind.DEPART || event.kind == SimEvent.Kind.PASS || event.kind == SimEvent.Kind.RETURNED)).map(event -> ScenarioLine.clock(event.time) + " " + event.kind + " " + event.trainId + " " + event.rail).collect(Collectors.toList());
	}

	/**
	 * @param wholeCopies keep a whole copy of each duty's path for the other way round, as before 0.84.0, rather than the
	 *                    other way once
	 */
	static SimDataset dataset(boolean wholeCopies) {
		final SimDataset dataset = SimDatasetTest.lines(1, 400, 2, 4, 10);
		final DutyGenerator.Result result = transitsim.planning.PlanCheck.generate(dataset, 1, 0);
		final SimDataset.SidingData oldSiding = dataset.sidings.get(0);
		final List<SimDataset.DataTrack> sidingPath = oldSiding.path;
		final Map<Long, PlanRoute> routes = dataset.getPlanRoutes();
		final List<String> keys = new ArrayList<>();
		final List<List<Long>> shapes = new ArrayList<>();
		final List<transitsim.plan.DepotDuties.Slot> slots = result.sidings.get(oldSiding.id);
		for (int i = 0; i < slots.size(); i++) {
			for (int weekday = 0; weekday < 7; weekday++) {
				final String key = DutyShapes.getSlotKey(slots, i, weekday, routes::get);
				if (key != null && !keys.contains(key)) {
					keys.add(key);
					shapes.add(slots.get(i).getDuty(weekday).segments.stream().map(segment -> segment.routeId).collect(Collectors.toList()));
				}
			}
		}
		final int pointCount = (int) shapes.stream().filter(shape -> shape.equals(Arrays.asList(UP, DOWN))).count();
		assertTrue(pointCount > 0, "duties running up and down");

		// The other way round: from platform 2 into platform 9 and back out to platform 5, as long as the usual way
		final List<SimDataset.DataTrack> otherWay = Arrays.asList(sidingPath.get(STOP_RAIL), SimDatasetTest.plain(5, 30, 200), SimDatasetTest.plain(30, 31, 200), TurnBackWaySpliceTest.platform(31, 32, 3, 20), TurnBackWaySpliceTest.platform(32, 31, 3, 0), SimDatasetTest.plain(31, 15, 400), sidingPath.get(STOP_RAIL + USUAL_LENGTH));

		final List<SimDataset.TripPathData> tripPaths = new ArrayList<>();
		final List<TurnBackPoint> points = new ArrayList<>();
		final List<SimDataset.DataTrack> wayKept = new ArrayList<>();
		final List<long[]> wayPoints = new ArrayList<>();
		for (int i = 0; i < keys.size(); i++) {
			final String key = keys.get(i);
			final List<Long> shape = shapes.get(i);
			if (!shape.equals(Arrays.asList(UP, DOWN))) {
				if (shape.equals(Arrays.asList(UP)) || shape.equals(Arrays.asList(DOWN))) {
					tripPaths.add(PlanCheckTest.dutyPath(sidingPath, key, shape, false));
				}
				continue;
			}
			final int point = points.size();
			final List<SimDataset.DataTrack> path = dutyTracks(sidingPath);
			points.add(new TurnBackPoint(Arrays.asList(new ArrayList<>(path.subList(STOP_RAIL, STOP_RAIL + USUAL_LENGTH + 1)), otherWay), 0, 0));
			final DutyPlan<SimDataset.DataTrack, Object> dutyPlan = new DutyPlan<>(key, shape, new HashMap<>(), new HashMap<>(), new HashMap<>(), path.subList(2, 12), new int[]{0, 5}, null, null, false, false);
			dutyPlan.segmentsKnown = true;
			final int[] stops = new int[pointCount];
			Arrays.fill(stops, -1);
			stops[point] = STOP_RAIL;
			tripPaths.add(write(new TripPath<>(dutyPlan, new int[]{2, 7}, path, key, -1, -1, stops)));
			if (wholeCopies) {
				final List<SimDataset.DataTrack> wholePath = new ArrayList<>(path.subList(0, STOP_RAIL + 1));
				wholePath.addAll(otherWay.subList(1, otherWay.size() - 1));
				wholePath.addAll(path.subList(STOP_RAIL + USUAL_LENGTH, path.size()));
				tripPaths.add(write(new TripPath<>(dutyPlan, new int[]{2, 8}, wholePath, TripPath.getTurnBackKey(key, point, 1), point, 1, stops.clone())));
			} else {
				wayPoints.add(new long[]{point, USUAL_LENGTH, -1, 0});
			}
		}
		final int[] depotStops = new int[points.size()];
		Arrays.fill(depotStops, -1);
		final BinaryKeyed.Writer turnBackValues = new BinaryKeyed.Writer();
		try {
			TurnBacks.of(depotStops, points.toArray(new TurnBackPoint[0])).write(turnBackValues);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		final ByteArrayBinary.Writer turnBackWriter = new ByteArrayBinary.Writer();
		turnBackValues.writeTo(turnBackWriter);
		final SimDataset.SidingData siding = new SimDataset.SidingData(oldSiding.id, oldSiding.depotId, oldSiding.routeIds, oldSiding.trainCars, oldSiding.spacing, oldSiding.railLength, oldSiding.acceleration, oldSiding.deceleration, oldSiding.maxTrains, oldSiding.usesSpeedCurve, oldSiding.minSpeed, oldSiding.repeatIndex1, oldSiding.repeatIndex2, oldSiding.loopJoinIndex, sidingPath, turnBackWriter.toBytes());
		siding.tripPaths.addAll(tripPaths);
		if (!wholeCopies) {
			wayKept.addAll(otherWay);
			siding.turnBackWays.add(wayKept);
			siding.turnBackWayPoints.addAll(wayPoints);
		}
		dataset.sidings.set(0, siding);
		final transitsim.plan.DepotDuties duties = new transitsim.plan.DepotDuties();
		duties.diagramMode = true;
		duties.sidings.putAll(result.sidings);
		dataset.replaceDuties(1, duties.toBytes());
		return dataset;
	}

	/**
	 * The path of a duty running up and down, as {@link PlanCheckTest#dutyPath} makes it.
	 */
	private static List<SimDataset.DataTrack> dutyTracks(List<SimDataset.DataTrack> sidingPath) {
		final List<SimDataset.DataTrack> path = new ArrayList<>();
		int stopIndex = 1;
		for (int i = 0; i < sidingPath.size(); i++) {
			final SimDataset.DataTrack track = sidingPath.get(i);
			final boolean stops = i >= 2 && i <= 11;
			if (i > 0 && track.isPlatformTrack() && track.getStopId() != 0 && !sidingPath.get(i - 1).isPlatformTrack()) {
				stopIndex++;
			}
			path.add(new SimDataset.DataTrack(track.getStartNode(), track.getEndNode(), track.getTrackLength(), track.isPlatformTrack(), track.isSidingTrack(), track.getStopId(), stops || i == 0 || i == sidingPath.size() - 1 ? track.getDwellTime() : 0, i == 0 ? 0 : stopIndex, track.getMaxSpeed(), track.canAccelerate(), track.hasSignal(), track.isTwoWay()));
		}
		return path;
	}

	private static SimDataset.TripPathData write(TripPath<SimDataset.DataTrack> tripPath) {
		final BinaryKeyed.Writer values = new BinaryKeyed.Writer();
		try {
			tripPath.write(values);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
		values.writeTo(writer);
		return new SimDataset.TripPathData(writer.toBytes(), tripPath.path);
	}
}
