package transitsim.sim;

import org.junit.jupiter.api.Test;
import transitsim.motion.SpeedCurves;
import transitsim.plan.DutyPlan;
import transitsim.plan.DutyShapes;
import transitsim.plan.PathTimes;
import transitsim.plan.PathVariants;
import transitsim.plan.StopTimes;
import transitsim.plan.TimeGenerator;
import transitsim.plan.TimeSegment;
import transitsim.plan.TripPath;
import transitsim.plan.TurnBackSection;
import transitsim.plan.TurnBackWayStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A duty turning back in front of its terminus many times a day (站前折返, the user's 明珠线: platforms 6 and 5 of 田端 in
 * turn) keeps each other way round once, not a whole copy of its day for each turn, and the path a train taking the other
 * way at one turn runs is the one the siding used to keep (0.84.0).
 */
class TurnBackWaySpliceTest {

	private static final int TURNS = 160;

	@Test
	void aDutyWithManyFrontTurnBacksKeepsTheOtherWayOnce() {
		final Duty duty = new Duty(TURNS);
		final TurnBackWayStore<SimDataset.DataTrack> store = duty.store();
		assertEquals(1, store.getWays().size(), "one distinct way for all the turns");
		assertEquals(TURNS, store.pointCount());
		assertEquals(7, store.get(5, 1).size());
		assertEquals(5, store.getUsualLength(5));
		assertNull(store.get(5, 0), "the usual way is the duty's own");
		assertNull(store.get(TURNS, 1));
	}

	@Test
	void theSplicedPathIsTheWholeCopyKeptBefore() {
		final Duty duty = new Duty(12);
		final TripPath<SimDataset.DataTrack> base = duty.base();
		final PathVariants<SimDataset.DataTrack> variants = duty.variants(duty.store());
		for (final int point : new int[]{0, 1, 5, 11}) {
			final TripPath<SimDataset.DataTrack> spliced = TripPath.withTurnBackWay(base, point, 1, variants);
			assertNotNull(spliced);
			final TripPath<SimDataset.DataTrack> whole = duty.wholeCopy(point, 1);
			assertSamePath(whole, spliced);
		}
		// Not the usual way, a point the duty doesn't have, or a path already taking another way
		assertNull(TripPath.withTurnBackWay(base, 3, 0, variants));
		assertNull(TripPath.withTurnBackWay(base, 12, 1, variants));
		assertNull(TripPath.withTurnBackWay(TripPath.withTurnBackWay(base, 3, 1, variants), 4, 1, variants));
	}

	@Test
	void theKeyOfAnotherWayRoundIsReadBack() {
		final String key = TripPath.getTurnBackKey(DutyShapes.KEY_PREFIX + "0|1@0-2:000>depot", 17, 1);
		assertArrayEquals(new int[]{17, 1}, TripPath.getTurnBackPointWay(key));
		assertEquals(DutyShapes.KEY_PREFIX + "0|1@0-2:000>depot", TripPath.getTurnBackBase(key));
		assertNull(TripPath.getTurnBackPointWay(DutyShapes.KEY_PREFIX + "0|1@0-2:000>depot"));
		assertNull(TripPath.getTurnBackPointWay(TripPath.getSkipKey(key, new int[]{3})));
		assertNull(TripPath.getTurnBackPointWay(TripPath.getDepotReturnKey(2) + "x"));
	}

	/**
	 * Measures making the paths of a duty of {@value #TURNS} turns as before (the usual path and a whole copy for each turn)
	 * against now (the usual path and the one way), and making one path when a train takes the other way.
	 */
	@Test
	void makingThePathsNoLongerTakesAWholeCopyPerTurn() {
		final Duty duty = new Duty(TURNS);
		// Warm up
		duty.base();
		duty.wholeCopy(0, 1);

		final long newStart = System.nanoTime();
		final TripPath<SimDataset.DataTrack> base = duty.base();
		final TurnBackWayStore<SimDataset.DataTrack> store = duty.store();
		final long newNanos = System.nanoTime() - newStart;

		final long oldStart = System.nanoTime();
		int oldRails = base.path.size();
		for (int point = 0; point < TURNS; point++) {
			oldRails += duty.wholeCopy(point, 1).path.size();
		}
		final long oldNanos = System.nanoTime() - oldStart + newNanos;

		final PathVariants<SimDataset.DataTrack> variants = duty.variants(store);
		final long spliceStart = System.nanoTime();
		for (int point = 0; point < 20; point++) {
			assertNotNull(TripPath.withTurnBackWay(base, point * 7, 1, variants));
		}
		final long spliceNanos = (System.nanoTime() - spliceStart) / 20;
		final int newRails = base.path.size() + store.getWays().stream().mapToInt(List::size).sum();
		System.out.printf("Duty of %d front turn-backs, %d rails: before %d ms (%d paths, %d rails kept), now %d ms (1 path and %d way, %d rails kept); one other way made on demand %.2f ms%n",
				TURNS, base.path.size(), oldNanos / 1000000, TURNS + 1, oldRails, newNanos / 1000000, store.getWays().size(), newRails, spliceNanos / 1e6);
	}

	private static void assertSamePath(TripPath<SimDataset.DataTrack> expected, TripPath<SimDataset.DataTrack> actual) {
		assertEquals(expected.key, actual.key);
		assertEquals(expected.tripKey, actual.tripKey);
		assertEquals(expected.turnBackPoint, actual.turnBackPoint);
		assertEquals(expected.turnBackWay, actual.turnBackWay);
		assertArrayEquals(expected.dutyStarts, actual.dutyStarts);
		assertArrayEquals(expected.turnBackStops, actual.turnBackStops);
		assertEquals(expected.routeIds, actual.routeIds);
		assertEquals(describe(expected.path), describe(actual.path));
		assertEquals(expected.timeSegments.size(), actual.timeSegments.size());
		for (int i = 0; i < expected.timeSegments.size(); i++) {
			final TimeSegment expectedSegment = expected.timeSegments.get(i);
			final TimeSegment actualSegment = actual.timeSegments.get(i);
			assertEquals(expectedSegment.endTime, actualSegment.endTime, "time segment " + i);
			assertEquals(expectedSegment.savedRailBaseId, actualSegment.savedRailBaseId, "time segment " + i);
		}
		assertEquals(expected.stopTimes.getDurationMillis(), actual.stopTimes.getDurationMillis());
		assertEquals(expected.distances, actual.distances);
	}

	private static List<String> describe(List<SimDataset.DataTrack> path) {
		final List<String> lines = new ArrayList<>();
		path.forEach(track -> lines.add(track.getStartNode() + ">" + track.getEndNode() + "@" + track.getStopId() + "/" + track.getDwellTime() + "#" + track.getStopIndex()));
		return lines;
	}

	static String signature(List<SimDataset.DataTrack> tracks) {
		final StringBuilder builder = new StringBuilder();
		tracks.forEach(track -> builder.append(track.getStartNode()).append('>').append(track.getEndNode()).append('@').append(track.getStopId()).append('/').append(track.getDwellTime()).append(track.isPlatformTrack() ? 'p' : '-').append(';'));
		return builder.toString();
	}

	static SimDataset.DataTrack platform(long from, long to, long platformId, int dwellTime) {
		return new SimDataset.DataTrack(from, to, 30, true, false, platformId, dwellTime, 0, 1, false, true, true);
	}

	/**
	 * Up from platform 1 by platform 2 to platform 3, turning back on it, and down by platform 5 to platform 4, again and
	 * again. The other way round at each turn runs from platform 2 into platform 9 of the same station instead and back
	 * out to platform 5, one rail longer than the usual way.
	 */
	static final class Duty {

		final List<SimDataset.DataTrack> body = new ArrayList<>();
		final int[] starts;
		final List<TurnBackSection<SimDataset.DataTrack>> sections = new ArrayList<>();
		final DutyPlan<SimDataset.DataTrack, Object> plan;
		final TimeGenerator<SimDataset.DataTrack> generator = generator();

		Duty(int turns) {
			final List<Integer> startList = new ArrayList<>();
			final List<Long> routeIds = new ArrayList<>();
			for (int turn = 0; turn < turns; turn++) {
				startList.add(body.size());
				routeIds.add(1L);
				body.add(platform(2, 3, 1, 20));
				body.add(SimDatasetTest.plain(3, 4, 400));
				final int stop = body.size();
				body.add(platform(4, 5, 2, 20));
				body.add(SimDatasetTest.plain(5, 6, 400));
				body.add(platform(6, 7, 3, 20));
				startList.add(body.size());
				routeIds.add(2L);
				body.add(platform(7, 6, 3, 0));
				body.add(SimDatasetTest.plain(6, 15, 400));
				body.add(platform(15, 14, 5, 20));
				final List<SimDataset.DataTrack> usual = new ArrayList<>(body.subList(stop, body.size()));
				// Each turn's way found again by the host, the same rails as the others
				final List<SimDataset.DataTrack> other = Arrays.asList(platform(4, 5, 2, 0), SimDatasetTest.plain(5, 30, 200), SimDatasetTest.plain(30, 31, 200), platform(31, 32, 3, 20), platform(32, 31, 3, 0), SimDatasetTest.plain(31, 15, 400), platform(15, 14, 5, 0));
				sections.add(new TurnBackSection<>(stop, Arrays.asList(usual, other), 0));
				body.add(SimDatasetTest.plain(14, 13, 400));
				body.add(platform(13, 12, 4, 20));
				body.add(SimDatasetTest.plain(12, 2, 60));
			}
			int stopIndex = 0;
			for (final SimDataset.DataTrack track : body) {
				if (track.getDwellTime() > 0) {
					stopIndex++;
				}
				track.setStopIndex(stopIndex);
			}
			starts = startList.stream().mapToInt(Integer::intValue).toArray();
			plan = new DutyPlan<>(DutyShapes.KEY_PREFIX + "0|test>stay", routeIds, new HashMap<>(), new HashMap<>(), new HashMap<>(), body, starts, null, null, true, true);
			plan.segmentsKnown = true;
			plan.turnBackSections.addAll(sections);
		}

		int[] stops() {
			final int[] stops = new int[sections.size()];
			for (int i = 0; i < stops.length; i++) {
				stops[i] = sections.get(i).start;
			}
			return stops;
		}

		TripPath<SimDataset.DataTrack> base() {
			final TripPath<SimDataset.DataTrack> base = new TripPath<>(plan, starts.clone(), body, plan.key, -1, -1, stops());
			base.generateTimes(generator);
			return base;
		}

		TurnBackWayStore<SimDataset.DataTrack> store() {
			final TurnBackWayStore.Builder<SimDataset.DataTrack> builder = new TurnBackWayStore.Builder<>(TurnBackWaySpliceTest::signature);
			for (int i = 0; i < sections.size(); i++) {
				builder.add(i, sections.get(i), plan.routeIds.get(0));
			}
			return builder.build();
		}

		PathVariants<SimDataset.DataTrack> variants(TurnBackWayStore<SimDataset.DataTrack> store) {
			return new PathVariants<SimDataset.DataTrack>() {
				@Override
				public SimDataset.DataTrack pass(SimDataset.DataTrack track) {
					return track.passed();
				}

				@Override
				public TimeGenerator<SimDataset.DataTrack> getTimeGenerator() {
					return generator;
				}

				@Override
				public TurnBackWayStore<SimDataset.DataTrack> getTurnBackWays() {
					return store;
				}
			};
		}

		/**
		 * The duty's whole path taking another way round one point, as the game's siding made it for each point and way
		 * before 0.84.0 ({@code Siding.DutyBody}).
		 */
		TripPath<SimDataset.DataTrack> wholeCopy(int changed, int way) {
			final TurnBackSection<SimDataset.DataTrack> section = sections.get(changed);
			final List<SimDataset.DataTrack> newWay = section.ways.get(way);
			final int length = section.getLength();
			final int changedStart = section.start;
			final int changedEnd = section.start + length - 1;
			final int extraRails = newWay.size() - length;
			final List<SimDataset.DataTrack> path = new ArrayList<>(body.subList(0, section.start + 1));
			path.addAll(newWay.subList(1, newWay.size() - 1));
			path.addAll(body.subList(section.start + length - 1, body.size()));
			final int[] newStarts = new int[starts.length];
			for (int i = 0; i < newStarts.length; i++) {
				newStarts[i] = starts[i] + (starts[i] > changedStart ? extraRails : 0);
			}
			final int[] newStops = new int[sections.size()];
			for (int i = 0; i < newStops.length; i++) {
				final int start = sections.get(i).start;
				newStops[i] = i != changed && start > changedStart && start < changedEnd ? -1 : start + (start > changedStart ? extraRails : 0);
			}
			final TripPath<SimDataset.DataTrack> tripPath = new TripPath<>(plan, newStarts, path, TripPath.getTurnBackKey(plan.key, changed, way), changed, way, newStops);
			tripPath.generateTimes(generator);
			return tripPath;
		}
	}

	/**
	 * Times paths on speed curves as the component's own data does, for a train of four cars.
	 */
	static TimeGenerator<SimDataset.DataTrack> generator() {
		final double trainLength = 4 * 10;
		return new TimeGenerator<SimDataset.DataTrack>() {
			@Override
			public StopTimes calculateStopTimes(List<SimDataset.DataTrack> path) {
				final StopTimes stopTimes = new StopTimes();
				generateTimeSegments(path, new ArrayList<>(), stopTimes, 0, 0);
				return stopTimes;
			}

			@Override
			public void generateTimeSegments(List<SimDataset.DataTrack> path, List<TimeSegment> timeSegments, StopTimes stopTimes, int joinIndex, int lapEndIndex) {
				timeSegments.clear();
				stopTimes.clear();
				stopTimes.setJoinIndex(joinIndex);
				if (!path.isEmpty()) {
					final SpeedCurves curves = SpeedCurves.of(RunTrack.toTrackView(path, 0.5F, 0, trainLength), (50 + trainLength) / 2, trainLength, 0.01F, 0.02F);
					PathTimes.curveTimes(path, curves, timeSegments, new HashMap<>(), stopTimes);
				}
				stopTimes.linkJoinStops(lapEndIndex);
			}
		};
	}
}
