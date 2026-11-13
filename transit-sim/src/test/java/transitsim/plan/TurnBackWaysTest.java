package transitsim.plan;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which ways round between two services are kept and which the path takes (step 2c of splitting the depot, 2026-11-13).
 */
class TurnBackWaysTest {

	/**
	 * A rail named by where it starts and ends, so a way is written as "a-b,b-c".
	 */
	private static final class Track implements PlanTrack {

		private final String from;
		private final String to;
		private final double length;
		private int stopIndex;

		Track(String from, String to, double length) {
			this.from = from;
			this.to = to;
			this.length = length;
		}

		@Override
		public double getTrackLength() {
			return length;
		}

		@Override
		public int getDwellTime() {
			return 0;
		}

		@Override
		public boolean isPlatformTrack() {
			return false;
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
			return track instanceof Track && ((Track) track).from.equals(to) && ((Track) track).to.equals(from);
		}

		@Override
		public UUID getTrackKey() {
			return new UUID(from.hashCode(), to.hashCode());
		}

		@Override
		public String toString() {
			return from + "-" + to;
		}
	}

	/**
	 * @param way rails as "a-b,b-c", each 10 blocks long unless written as "a-b:30"
	 */
	private static List<Track> way(String way) {
		final List<Track> tracks = new ArrayList<>();
		if (!way.isEmpty()) {
			for (final String rail : way.split(",")) {
				final String[] parts = rail.split(":");
				final String[] ends = parts[0].split("-");
				tracks.add(new Track(ends[0], ends[1], parts.length > 1 ? Double.parseDouble(parts[1]) : 10));
			}
		}
		return tracks;
	}

	private static String text(List<Track> way) {
		final StringBuilder builder = new StringBuilder();
		way.forEach(track -> builder.append(builder.length() == 0 ? "" : ",").append(track));
		return builder.toString();
	}

	/**
	 * The ways the host would find, by node.
	 */
	private static final class Found implements TurnBackWays.Ways<String, Track> {

		private final Map<String, String> ways = new LinkedHashMap<>();
		final List<String> unusable = new ArrayList<>();

		Found put(String node, String way) {
			ways.put(node, way);
			return this;
		}

		@Override
		public List<Track> find(String node) {
			return way(ways.getOrDefault(node, ""));
		}

		@Override
		public boolean sameRail(Track track, Track other) {
			return track.from.equals(other.from) && track.to.equals(other.to);
		}

		@Override
		public void unusable(String node) {
			unusable.add(node);
		}
	}

	@Test
	void theFirstWayThatCanBeUsedIsTheDefaultAndSetsWhereTheOthersLeaveAndArrive() {
		final Found found = new Found()
				// Only one rail: a train has nowhere to run
				.put("short", "a-b")
				// The first usable one
				.put("near", "a-b,b-n,n-b,b-c")
				// Leaves and arrives the same way: usable
				.put("far", "a-b,b-f,f-b,b-c")
				// Leaves along another rail: a train couldn't take it from the same place
				.put("other", "x-b,b-o,o-b,b-c");
		final TurnBackSection<Track> section = TurnBackWays.section(Arrays.asList("short", "near", "far", "other"), 7, found);
		assertEquals(7, section.start);
		assertEquals(1, section.defaultWay);
		assertEquals("a-b,b-n,n-b,b-c", text(section.getDefaultWay()));
		assertNull(section.ways.get(0));
		assertEquals("a-b,b-f,f-b,b-c", text(section.ways.get(2)));
		assertNull(section.ways.get(3));
		assertEquals(Arrays.asList("short", "other"), found.unusable);
	}

	@Test
	void noWayThatCanBeUsedIsNoSection() {
		final Found found = new Found().put("short", "a-b");
		assertNull(TurnBackWays.section(Collections.singletonList("short"), 0, found));
		assertNull(TurnBackWays.section(Collections.<String>emptyList(), 0, found));
		assertEquals(Collections.singletonList("short"), found.unusable);
	}

	@Test
	void whatTheRouteListsIsTakenWhateverItsLength() {
		final Found found = new Found().put("listed", "a-b,b-l:200,l-b:200,b-c");
		final List<Track> into = new ArrayList<>();
		final boolean[] asked = {false, false};
		final TurnBackSection<Track> section = TurnBackWays.connection(into, Collections.singletonList("listed"), () -> {
			asked[0] = true;
			return way("a-b,b-c");
		}, () -> {
			asked[1] = true;
			return Collections.singletonList("near");
		}, found);
		assertNotNull(section);
		assertEquals("a-b,b-l,l-b,b-c", text(into));
		// Neither the way straight through nor the nodes near the stop are asked for
		assertArrayEquals(new boolean[]{false, false}, asked);
	}

	@Test
	void listedNodesWithNoWayRoundLeaveNoConnection() {
		final List<Track> into = new ArrayList<>();
		assertNull(TurnBackWays.connection(into, Collections.singletonList("listed"), () -> way("a-b,b-c"), () -> Collections.singletonList("near"), new Found()));
		assertTrue(into.isEmpty());
	}

	@Test
	void withNothingListedTheShorterOfTheWayThroughAndTheWayRoundIsTaken() {
		final Found found = new Found().put("near", "a-b,b-n,n-b,b-c");
		// Round the turn back rail: 40 blocks against 100 straight through
		final List<Track> round = new ArrayList<>();
		assertNotNull(TurnBackWays.connection(round, Collections.<String>emptyList(), () -> way("a-c:100"), () -> Collections.singletonList("near"), found));
		assertEquals("a-b,b-n,n-b,b-c", text(round));
		// The way straight through is shorter: kept, and no section
		final List<Track> through = new ArrayList<>();
		assertNull(TurnBackWays.connection(through, Collections.<String>emptyList(), () -> way("a-c:30"), () -> Collections.singletonList("near"), found));
		assertEquals("a-c", text(through));
		// As long either way: the way straight through stays
		final List<Track> same = new ArrayList<>();
		assertNull(TurnBackWays.connection(same, Collections.<String>emptyList(), () -> way("a-c:40"), () -> Collections.singletonList("near"), found));
		assertEquals("a-c", text(same));
	}

	@Test
	void withNoWayThroughTheWayRoundIsTakenAndWithNeitherThereIsNone() {
		final Found found = new Found().put("near", "a-b,b-n,n-b,b-c");
		final List<Track> round = new ArrayList<>();
		assertNotNull(TurnBackWays.connection(round, Collections.<String>emptyList(), () -> way(""), () -> Collections.singletonList("near"), found));
		assertEquals("a-b,b-n,n-b,b-c", text(round));
		// No node near the stop: the way straight through, with no section
		final List<Track> through = new ArrayList<>();
		assertNull(TurnBackWays.connection(through, Collections.<String>emptyList(), () -> way("a-c:30"), Collections::<String>emptyList, found));
		assertEquals("a-c", text(through));
		// Neither: nothing at all
		final List<Track> none = new ArrayList<>();
		assertNull(TurnBackWays.connection(none, Collections.<String>emptyList(), () -> way(""), Collections::<String>emptyList, found));
		assertTrue(none.isEmpty());
	}

	@Test
	void aWayIsAsLongAsItsRails() {
		assertEquals(0, TurnBackWays.length(Collections.<Track>emptyList()));
		assertEquals(45, TurnBackWays.length(way("a-b:20,b-c:25")));
	}
}
