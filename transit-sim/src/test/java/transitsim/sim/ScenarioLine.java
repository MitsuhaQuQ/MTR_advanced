package transitsim.sim;

import transitsim.motion.MotionEvent;
import transitsim.motion.SpeedCurves;
import transitsim.plan.OperatingDay;
import transitsim.plan.PathTimes;
import transitsim.plan.StopTimes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Three stations up, turning back at the single platform of the last one, and two back down to the depot, on double
 * track. A service leaves every 10 minutes each way from 06:00 until 08:00.
 */
final class ScenarioLine {

	static final long SECOND = 1000;
	static final long MINUTE = 60 * SECOND;
	static final long HOUR = 60 * MINUTE;
	/**
	 * Monday the 5th of January 2026 at midnight in UTC.
	 */
	static final long MONDAY = 20458L * OperatingDay.MILLIS_PER_DAY;
	static final long SIDING = 100;


	final TestRoute up = makeRoute(1, 0, 1, 2, 3);
	final TestRoute down = makeRoute(2, 5, 3, 5, 4);
	final TickRunner<ScenarioTrack> runner;
	private final Map<Long, Runnable> actions = new TreeMap<>();

	/**
	 * @param between   the length of the line between stations
	 * @param trainCars the cars of each train, 10 long
	 * @param maxTrains how many trains the siding has out at most
	 */
	ScenarioLine(double between, int trainCars, int maxTrains) {
		// The siding takes the whole train
		final float sidingLength = trainCars * 10 + 10;
		final ScenarioTrack terminal = ScenarioTrack.platform(6, 7, 3, 20, true);
		final List<ScenarioTrack> path = Arrays.asList(
				ScenarioTrack.siding(0, 1, SIDING, sidingLength),
				ScenarioTrack.plain(1, 2, 60, false),
				ScenarioTrack.platform(2, 3, 1, 20, false),
				ScenarioTrack.plain(3, 4, between, false),
				ScenarioTrack.platform(4, 5, 2, 20, false),
				ScenarioTrack.plain(5, 6, between, false),
				terminal,
				terminal.reversed(0),
				ScenarioTrack.plain(6, 15, between, false),
				ScenarioTrack.platform(15, 14, 5, 20, false),
				ScenarioTrack.plain(14, 13, between, false),
				ScenarioTrack.platform(13, 12, 4, 20, false),
				ScenarioTrack.plain(12, 1, 60, false),
				ScenarioTrack.siding(0, 1, SIDING, sidingLength).reversed(0)
		);
		final Map<Long, TestRoute> routes = new HashMap<>();
		routes.put(up.id, up);
		routes.put(down.id, down);
		runner = new TickRunner<>(MONDAY + 5 * HOUR + 50 * MINUTE, routes::get);
		final TickRunner.Siding<ScenarioTrack> unTimed = new TickRunner.Siding<>(path, 0, 0, null, "train", trainCars, 10, sidingLength, 0.01F, 0.02F, maxTrains);
		runner.addSiding(SIDING, new DepotBoard(Arrays.asList(up, down)), new TickRunner.Siding<>(path, 0, 0, curveStopTimes(unTimed), "train", trainCars, 10, sidingLength, 0.01F, 0.02F, maxTrains));
	}

	/**
	 * Does something once the runner gets to this time.
	 */
	void at(long millis, Runnable action) {
		actions.put(millis, action);
	}

	/**
	 * @return each train's departures, stops and returns to the depot in order, as {@code train rail what hh:mm:ss.t}
	 */
	List<String> run(long endMillis) {
		actions.forEach((millis, action) -> {
			runner.runUntil(millis);
			action.run();
		});
		runner.runUntil(endMillis);
		final List<String> lines = new ArrayList<>();
		for (final MotionEvent event : runner.getEvents()) {
			if (event.type == MotionEvent.Type.STOPPED || event.type == MotionEvent.Type.DEPARTED || event.type == MotionEvent.Type.RETURNED) {
				lines.add(String.format("%d %d %s %s", event.trainId, event.rail, event.type, clock(event.millis)));
			}
		}
		return lines;
	}
	
	/**
	 * @return the fastest times along the siding's path, worked out as the game's siding does
	 */
	static StopTimes curveStopTimes(TickRunner.Siding<ScenarioTrack> siding) {
		final SpeedCurves curves = SpeedCurves.of(RunTrack.toTrackView(siding.path, siding.minSpeed), (siding.railLength + siding.trainCars * siding.spacing) / 2, siding.trainCars * siding.spacing, siding.acceleration, siding.deceleration);
		final StopTimes stopTimes = new StopTimes();
		PathTimes.curveTimes(siding.path, curves, new ArrayList<>(), new HashMap<>(), stopTimes);
		stopTimes.linkJoinStops(0);
		return stopTimes;
	}

	/**
	 * @param firstMinute the minute past each hour of the services, which run every 10 minutes from 06:00 until 08:00
	 */
	static TestRoute makeRoute(long id, int firstMinute, long... platformIds) {
		final TestRoute route = new TestRoute(id, platformIds);
		route.timetable.enabled = true;
		route.timetable.marginPercent = 5;
		for (long millis = 6 * HOUR + firstMinute * MINUTE; millis < 8 * HOUR; millis += 10 * MINUTE) {
			route.timetable.services.add((int) millis);
		}
		route.timetable.sanitize();
		return route;
	}

	static String clock(long millis) {
		final long inDay = Math.floorMod(millis, OperatingDay.MILLIS_PER_DAY);
		return String.format("%02d:%02d:%02d.%d", inDay / HOUR, inDay / MINUTE % 60, inDay / SECOND % 60, inDay % SECOND / 100);
	}
}
