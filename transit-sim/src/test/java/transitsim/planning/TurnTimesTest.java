package transitsim.planning;

import org.junit.jupiter.api.Test;
import transitsim.plan.PlanTrack;
import transitsim.plan.TimeSegment;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Times between services at a station as the planner reads them (step 2b of splitting the depot, 2026-11-13).
 */
class TurnTimesTest {

	private static final class Track implements PlanTrack {
		private final long start;
		private final long end;
		private final double length;
		private final int dwell;
		private final double stopOffset;

		Track(long start, long end, double length, int dwell, double stopOffset) {
			this.start = start;
			this.end = end;
			this.length = length;
			this.dwell = dwell;
			this.stopOffset = stopOffset;
		}

		Track reversed() {
			return new Track(end, start, length, 0, 0);
		}

		@Override
		public double getTrackLength() {
			return length;
		}

		@Override
		public int getDwellTime() {
			return dwell;
		}

		@Override
		public boolean isPlatformTrack() {
			return dwell > 0;
		}

		@Override
		public boolean isSidingTrack() {
			return false;
		}

		@Override
		public long getStopId() {
			return 0;
		}

		@Override
		public double getStopOffset(double trainLength) {
			return stopOffset;
		}

		@Override
		public int getStopIndex() {
			return 0;
		}

		@Override
		public void setStopIndex(int stopIndex) {
		}

		@Override
		public boolean isOppositeOf(PlanTrack track) {
			return track instanceof Track && ((Track) track).start == end && ((Track) track).end == start;
		}

		@Override
		public UUID getTrackKey() {
			return new UUID(Math.min(start, end), Math.max(start, end));
		}
	}

	/**
	 * @return a segment at one block a tick
	 */
	private static TimeSegment steady(double from, double to, float startTime) {
		final TimeSegment segment = new TimeSegment(from, 1, startTime, 0, 0);
		segment.endRailProgress = to;
		segment.endTime = (float) (startTime + to - from);
		return segment;
	}

	@Test
	void theWayRoundIsCutWhereTheTrainTurnsBackLeavingOutStandingThere() {
		final Track in = new Track(1, 2, 10, 0, 0);
		final List<Track> way = Arrays.asList(new Track(0, 1, 4, 0, 0), in, in.reversed(), new Track(1, 0, 4, 0, 0));
		// In 0..14 by tick 14, standing 6 ticks; turning back the head goes to where the tail was (8 on, as the game times
		// it), out 22..36 from tick 20 to 34
		final List<TimeSegment> segments = Arrays.asList(steady(0, 14, 0), steady(22, 36, 20));
		assertArrayEquals(new long[]{700, 700}, TurnTimes.legs(way, segments, 8, 50));
		// A way with no turn back is all in
		assertArrayEquals(new long[]{700, 0}, TurnTimes.legs(Arrays.asList(new Track(0, 1, 4, 0, 0), in), Collections.singletonList(steady(0, 14, 0)), 8, 50));
		assertArrayEquals(new long[]{0, 0}, TurnTimes.legs(way, Collections.emptyList(), 8, 50));
	}

	@Test
	void aPlatformTheTrainStopsShortOnTurnsItBackWhereItStands() {
		// Stopping 4 short of the platform's end: in to 10, out from there
		final Track platform = new Track(1, 2, 10, 100, 4);
		final List<Track> way = Arrays.asList(new Track(0, 1, 4, 0, 0), platform, platform.reversed());
		// Turning back 8 plus twice the 4 on
		final List<TimeSegment> segments = Arrays.asList(steady(0, 10, 0), steady(26, 40, 15));
		assertArrayEquals(new long[]{500, 700}, TurnTimes.legs(way, segments, 8, 50));
	}

	@Test
	void slowestTakesTheLongestSumAndTheFirstWhenAsLong() {
		final long[] first = {300, 100};
		final long[] second = {100, 300};
		assertSame(first, TurnTimes.slowest(Arrays.asList(new long[]{100, 100}, first, second)));
		assertArrayEquals(new long[]{0, 0}, TurnTimes.slowest(Collections.emptyList()));
	}

	@Test
	void aTurnBackRailHasOneKeyEitherWayRoundAndNeverZero() {
		assertEquals(TurnTimes.railKey(5, 9), TurnTimes.railKey(9, 5));
		assertNotEquals(TurnTimes.railKey(5, 9), TurnTimes.railKey(5, 10));
		assertEquals(1, TurnTimes.railKey(0, 0));
	}

	/**
	 * Routes as their first and last platforms; platforms 10.. at station 1, 20.. at station 2.
	 */
	private static class Host implements TurnTimes.Host<long[], String> {
		final Map<Long, TurnTimes.Way<String>> holds = new HashMap<>();
		final Map<String, TurnTimes.Way<String>> changes = new HashMap<>();
		final List<String> asked = new ArrayList<>();
		final List<String> tooFar = new ArrayList<>();

		@Override
		public long lastPlatform(long[] route) {
			return route[1];
		}

		@Override
		public long firstPlatform(long[] route) {
			return route[0];
		}

		@Override
		public long station(long platform) {
			return platform / 10;
		}

		@Override
		public TurnTimes.Way<String> holdWay(long[] before, long platform) {
			asked.add("hold " + platform);
			return holds.get(platform);
		}

		@Override
		public TurnTimes.Way<String> changeWay(long[] before, long[] after, long from, long to) {
			asked.add(from + ">" + to);
			return changes.get(from + ">" + to);
		}

		@Override
		public void tooFar(long from, long to, TurnTimes.Way<String> way) {
			tooFar.add(from + ">" + to);
		}
	}

	private static TurnTimes.Way<String> way(double farthest, long places, long[] rail, long[]... legs) {
		return new TurnTimes.Way<>("way", Arrays.asList(legs), farthest, places, rail);
	}

	@Test
	void timesBetweenServicesAreKeptByPlatformAsThePlannerReadsThem() {
		final Host host = new Host();
		host.holds.put(11L, way(30, 2, new long[]{7, 3}, new long[]{20000, 15000}, new long[]{25000, 15000}));
		host.holds.put(12L, way(30, 1, null, new long[]{10000, 10000}));
		host.changes.put("11>12", way(40, 1, null, new long[]{30000, 20000}));
		host.changes.put("12>11", way(500, 1, null, new long[]{30000, 20000}));
		// Routes as first→last: 10→11, 12→11, 11→12, and 21→22 at another station
		final List<long[]> routes = Arrays.asList(new long[]{10, 11}, new long[]{12, 11}, new long[]{11, 12}, new long[]{21, 22});
		final Map<Long, Map<Long, Long>> times = TurnTimes.of(routes, host, 200);

		final Map<Long, Long> from11 = times.get(11L);
		// Round the turn back rail: the slowest vehicle's legs, the in leg, two places, the rail
		assertEquals(40000L, from11.get(11L));
		assertEquals(25000L, from11.get(DutyGenerator.HOLD_IN_KEY));
		assertEquals(2L, from11.get(DutyGenerator.HOLD_COUNT_KEY));
		assertEquals(TurnTimes.railKey(3, 7), from11.get(DutyGenerator.HOLD_RAIL_KEY));
		// To the other first platform at the same station
		assertEquals(50000L, from11.get(12L));
		// One place and no rail given: neither key
		final Map<Long, Long> from12 = times.get(12L);
		assertEquals(20000L, from12.get(12L));
		assertFalse(from12.containsKey(DutyGenerator.HOLD_COUNT_KEY));
		assertFalse(from12.containsKey(DutyGenerator.HOLD_RAIL_KEY));
		// Too far off to be a change of platform
		assertFalse(from12.containsKey(11L));
		assertEquals(Collections.singletonList("12>11"), host.tooFar);
		// Station 2: no way round 22 and none to 21
		assertFalse(times.containsKey(22L));
		// Each platform's way round asked once, a pair kept never again, a pair with no way asked again by the next route
		// ending there, never another station's platform nor the same platform
		assertEquals(Arrays.asList("hold 11", "11>10", "11>12", "11>10", "hold 12", "12>10", "12>11", "hold 22", "22>21"), host.asked);
	}

	@Test
	void aTrainTurningBackInFrontOfTheStationEndsAtTheNextRoutesFirstPlatform() {
		// Route 10→11 may end at 12 over a crossover: 11→12 is no change of platform, only changing ends; without the way in,
		// the way round is asked as before
		final Host host = new Host() {
			@Override
			public boolean turnsInto(long[] before, long[] after, long platform) {
				asked.add("front " + platform);
				return before[1] == 11 && platform == 12;
			}
		};
		host.changes.put("12>11", way(40, 1, null, new long[]{30000, 20000}));
		final Map<Long, Map<Long, Long>> times = TurnTimes.of(Arrays.asList(new long[]{10, 11}, new long[]{12, 13}, new long[]{13, 12}, new long[]{11, 14}), host, 200);
		assertEquals(0L, times.get(11L).get(12L));
		assertEquals(50000L, times.get(12L).get(11L));
		assertFalse(host.asked.contains("11>12"));
	}
}
