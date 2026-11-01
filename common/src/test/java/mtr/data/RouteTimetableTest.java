package mtr.data;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteTimetableTest {

	private static final long MINUTE = 60 * 1000;
	private static final long DAY = Depot.MILLISECONDS_PER_DAY;
	private static final long START = 20000 * DAY;

	private static RouteTimetable timetable(int... minutes) {
		final RouteTimetable timetable = new RouteTimetable();
		timetable.enabled = true;
		Arrays.stream(minutes).forEach(minute -> timetable.services.add((int) (minute * MINUTE)));
		timetable.sanitize();
		return timetable;
	}

	@Test
	void sanitizeSortsAndRemovesDuplicates() {
		final RouteTimetable timetable = timetable(30, 10, 30);
		timetable.services.add(-(int) MINUTE);
		timetable.marginPercent = 1000;
		timetable.sanitize();
		assertEquals(Arrays.asList((int) (10 * MINUTE), (int) (30 * MINUTE), (int) (DAY - MINUTE)), timetable.services);
		assertEquals(RouteTimetable.MAX_MARGIN_PERCENT, timetable.marginPercent);
	}

	@Test
	void emptyOrDisabledTimetableIsNotActive() {
		assertFalse(timetable().isActive());
		final RouteTimetable timetable = timetable(10);
		assertTrue(timetable.isActive());
		timetable.enabled = false;
		assertFalse(timetable.isActive());
	}

	@Test
	void nextServiceIncludesTheStartAndExcludesTheEnd() {
		final RouteTimetable timetable = timetable(10, 20);
		assertEquals(START + 10 * MINUTE, timetable.getNextService(START + 10 * MINUTE, START + DAY, time -> false));
		assertEquals(START + 20 * MINUTE, timetable.getNextService(START + 11 * MINUTE, START + DAY, time -> false));
		assertEquals(0, timetable.getNextService(START + 11 * MINUTE, START + 20 * MINUTE, time -> false));
	}

	@Test
	void nextServiceSkipsTakenServices() {
		final RouteTimetable timetable = timetable(10, 20, 30);
		final Set<Long> taken = new HashSet<>(Arrays.asList(START + 10 * MINUTE, START + 20 * MINUTE));
		assertEquals(START + 30 * MINUTE, timetable.getNextService(START, START + DAY, taken::contains));
	}

	@Test
	void nextServiceCrossesMidnight() {
		final RouteTimetable timetable = timetable(5, 23 * 60 + 55);
		assertEquals(START + DAY + 5 * MINUTE, timetable.getNextService(START + 23 * 60 * MINUTE + 56 * MINUTE, START + 2 * DAY, time -> false));
	}

	@Test
	void latestServiceFindsTheMostRecentOne() {
		final RouteTimetable timetable = timetable(10, 20, 30);
		assertEquals(START + 20 * MINUTE, timetable.getLatestService(START, START + 25 * MINUTE, time -> false));
		assertEquals(START + 20 * MINUTE, timetable.getLatestService(START, START + 20 * MINUTE, time -> false));
	}

	@Test
	void latestServiceNeverRepeatsADeployedService() {
		final RouteTimetable timetable = timetable(10, 20);
		assertEquals(0, timetable.getLatestService(START + 20 * MINUTE, START + 25 * MINUTE, time -> false));
	}

	@Test
	void latestServiceSkipsTakenServices() {
		final RouteTimetable timetable = timetable(10, 20);
		assertEquals(START + 10 * MINUTE, timetable.getLatestService(START, START + 25 * MINUTE, time -> time == START + 20 * MINUTE));
	}

	@Test
	void latestServiceCrossesMidnight() {
		final RouteTimetable timetable = timetable(23 * 60 + 55);
		assertEquals(START + 23 * 60 * MINUTE + 55 * MINUTE, timetable.getLatestService(START + 23 * 60 * MINUTE, START + DAY + MINUTE, time -> false));
	}

	private static RouteTimetable.StopPlan stop(long arrivalSeconds, long departureSeconds, int setArrival, int setDeparture, boolean overtake) {
		return new RouteTimetable.StopPlan(arrivalSeconds * 1000, departureSeconds * 1000, setArrival, setDeparture, overtake);
	}

	@Test
	void automaticTimesAddTheMarginToRunningTimeOnly() {
		final RouteTimetable timetable = timetable(10);
		timetable.marginPercent = 10;
		final List<RouteTimetable.StopPlan> stops = Arrays.asList(stop(0, 30, -1, -1, false), stop(130, 160, -1, -1, false));
		timetable.applyPlan(stops);

		assertEquals(30000, stops.get(0).departureMillis);
		assertEquals(140000, stops.get(1).arrivalMillis);
		assertEquals(170000, stops.get(1).departureMillis);
	}

	@Test
	void changingATimeMovesTheAutomaticTimesAfterIt() {
		final RouteTimetable timetable = timetable(10);
		timetable.marginPercent = 0;
		final List<RouteTimetable.StopPlan> stops = Arrays.asList(stop(0, 30, -1, -1, false), stop(130, 160, -1, 300, false), stop(260, 290, -1, -1, false));
		timetable.applyPlan(stops);

		assertEquals(130000, stops.get(1).arrivalMillis);
		assertEquals(300000, stops.get(1).departureMillis);
		assertEquals(400000, stops.get(2).arrivalMillis);
		assertEquals(430000, stops.get(2).departureMillis);
	}

	@Test
	void onlySetStopsKeepToTheirDeparture() {
		final RouteTimetable timetable = timetable(10);
		final List<RouteTimetable.StopPlan> stops = Arrays.asList(stop(0, 30, -1, -1, false), stop(100, 130, -1, -1, false), stop(200, 230, -1, 300, false), stop(400, 430, -1, -1, true));
		timetable.applyPlan(stops);

		assertTrue(stops.get(0).isTimed);
		assertFalse(stops.get(1).isTimed);
		assertTrue(stops.get(2).isTimed);
		assertTrue(stops.get(3).isTimed);
	}

	@Test
	void departureIsNeverBeforeArrivalAndTheFirstStopStartsAtZero() {
		final RouteTimetable timetable = timetable(10);
		timetable.marginPercent = 0;
		final List<RouteTimetable.StopPlan> stops = Arrays.asList(stop(0, 30, 500, -1, false), stop(100, 130, 200, 150, false));
		timetable.applyPlan(stops);

		assertEquals(0, stops.get(0).arrivalMillis);
		assertEquals(200000, stops.get(1).arrivalMillis);
		assertEquals(200000, stops.get(1).departureMillis);
	}

	@Test
	void trainsOnALoopCoverEveryServiceWithTheFewestTrains() {
		final RouteTimetable timetable = new RouteTimetable();
		timetable.enabled = true;
		for (int i = 0; i < 60; i++) {
			timetable.services.add((int) (6 * 60 * MINUTE + i * 5 * MINUTE));
		}
		timetable.sanitize();

		final long lap = 28 * MINUTE;
		final long dayStart = START;
		final Set<Long> reserved = new HashSet<>();
		int trains = 0;
		for (final int service : timetable.services) {
			final long tripMillis = dayStart + service;
			if (!reserved.contains(tripMillis)) {
				trains++;
				reserved.add(tripMillis);
			}
			final long nextTrip = timetable.getNextService(tripMillis + lap, tripMillis + lap + RouteTimetable.MAX_LAYOVER_MILLIS, reserved::contains);
			if (nextTrip != 0) {
				reserved.add(nextTrip);
			}
		}

		assertEquals(6, trains);
		assertEquals(60, reserved.size());
	}
}
