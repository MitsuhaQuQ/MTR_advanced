package transitsim.plan;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which platforms of the last station a train may end at and in what order, and when the ways make a section (step 2c of
 * splitting the depot, 2026-11-13).
 */
class FrontTurnBacksTest {

	/**
	 * A way as a single track, named by the platform it turns back at.
	 */
	private static final class Track implements PlanTrack {

		private final String name;
		private int stopIndex;

		Track(String name) {
			this.name = name;
		}

		@Override
		public double getTrackLength() {
			return 10;
		}

		@Override
		public int getDwellTime() {
			return 0;
		}

		@Override
		public boolean isPlatformTrack() {
			return true;
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
		public int getStopIndex() {
			return stopIndex;
		}

		@Override
		public void setStopIndex(int stopIndex) {
			this.stopIndex = stopIndex;
		}

		@Override
		public boolean isOppositeOf(PlanTrack track) {
			return false;
		}

		@Override
		public UUID getTrackKey() {
			return new UUID(0, name.hashCode());
		}

		@Override
		public String toString() {
			return name;
		}
	}

	private static List<Track> way(String name) {
		return Arrays.asList(new Track(name), new Track(name + "'"));
	}

	private static final List<Track> USUAL = way("usual");

	/**
	 * The ways the host would find, by platform; a platform not here has none.
	 */
	private static final class Found implements FrontTurnBacks.Ways<Track> {

		private final Set<Long> withWay = new HashSet<>();
		final List<Long> unusable = new ArrayList<>();

		Found with(long... platformIds) {
			for (final long platformId : platformIds) {
				withWay.add(platformId);
			}
			return this;
		}

		@Override
		public List<Track> find(long platformId) {
			return withWay.contains(platformId) ? way("at" + platformId) : null;
		}

		@Override
		public void unusable(long platformId) {
			unusable.add(platformId);
		}
	}

	@Test
	void thePlatformTheNextRouteWouldRatherStartFromComesFirst() {
		// Route 1 ends at 3 and lists 8 and 9 too; the next route starts at 3 and would rather start at 9
		final List<Long> ordered = FrontTurnBacks.order(3, 3, Arrays.asList(8L, 9L), Collections.singletonList(9L), platformId -> true);
		assertEquals(Arrays.asList(9L, 3L, 8L), ordered);
	}

	@Test
	void theUsualPlatformIsOneOfThemOnlyWhenTheNextRouteStartsThere() {
		// The next route starts at 4, not at 3 where the route before ends: only the listed platforms
		assertEquals(Arrays.asList(8L, 9L), FrontTurnBacks.order(3, 4, Arrays.asList(8L, 9L), Collections.<Long>emptyList(), platformId -> true));
		assertEquals(Arrays.asList(3L, 8L), FrontTurnBacks.order(3, 3, Collections.singletonList(8L), Collections.<Long>emptyList(), platformId -> true));
	}

	@Test
	void platformsOfAnotherStationOrGoneAreLeftOutAndEachComesOnce() {
		// 9 is at another station or deleted; 8 is listed twice, and the last platform listed again is not a second one
		final List<Long> ordered = FrontTurnBacks.order(3, 3, Arrays.asList(8L, 9L, 8L, 3L), Arrays.asList(8L, 8L, 9L), platformId -> platformId != 9);
		assertEquals(Arrays.asList(8L, 3L), ordered);
	}

	@Test
	void theUsualWayIsTheDefaultAndComesLastWhenTheTrainTurnsBackAfterTheStation() {
		// The next route starts at 4: the ways at the platforms come first, the usual way (站后) last
		final Found found = new Found().with(8, 9);
		final TurnBackSection<Track> section = FrontTurnBacks.section(5, 3, 4, Arrays.asList(8L, 9L), USUAL, found);
		assertEquals(5, section.start);
		assertEquals(2, section.defaultWay);
		assertSame(USUAL, section.getDefaultWay());
		assertEquals("at8", section.ways.get(0).get(0).toString());
		assertEquals("at9", section.ways.get(1).get(0).toString());
		assertTrue(found.unusable.isEmpty());
	}

	@Test
	void theUsualWayKeepsItsPlaceWhenTheTrainUsuallyTurnsBackAtItsLastPlatform() {
		final Found found = new Found().with(8);
		// Ordered 8, 3: the usual way is the second
		final TurnBackSection<Track> section = FrontTurnBacks.section(5, 3, 3, Arrays.asList(8L, 3L), USUAL, found);
		assertEquals(1, section.defaultWay);
		assertSame(USUAL, section.getDefaultWay());
		assertEquals(2, section.ways.size());
	}

	@Test
	void aPlatformWithNoWayIsKeptAsNullAndToldOf() {
		final Found found = new Found().with(8);
		final TurnBackSection<Track> section = FrontTurnBacks.section(5, 3, 3, Arrays.asList(8L, 3L, 9L), USUAL, found);
		assertEquals("at8", section.ways.get(0).get(0).toString());
		assertSame(USUAL, section.ways.get(1));
		assertNull(section.ways.get(2));
		assertEquals(Collections.singletonList(9L), found.unusable);
	}

	@Test
	void oneWayAloneIsNoChoiceAndMakesNoSection() {
		// Only the usual way can be used
		assertNull(FrontTurnBacks.section(5, 3, 3, Arrays.asList(3L, 8L), USUAL, new Found()));
		assertNull(FrontTurnBacks.section(5, 3, 4, Collections.<Long>emptyList(), USUAL, new Found()));
	}
}
