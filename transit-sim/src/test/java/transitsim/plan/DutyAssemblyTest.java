package transitsim.plan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a duty's segments are joined into its whole path (step 2d of splitting the depot, 2026-11-14), on tracks named by
 * the two nodes they run between.
 */
class DutyAssemblyTest {

	private static final class Track implements PlanTrack {

		final String from, to;
		final long stopId;
		final int dwellTime;
		private int stopIndex;

		Track(String from, String to, long stopId, int dwellTime) {
			this.from = from;
			this.to = to;
			this.stopId = stopId;
			this.dwellTime = dwellTime;
		}

		@Override
		public double getTrackLength() {
			return 10;
		}

		@Override
		public int getDwellTime() {
			return dwellTime;
		}

		@Override
		public boolean isPlatformTrack() {
			return stopId != 0;
		}

		@Override
		public boolean isSidingTrack() {
			return false;
		}

		@Override
		public long getStopId() {
			return stopId;
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
		public java.util.UUID getTrackKey() {
			return java.util.UUID.nameUUIDFromBytes((from + to).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}

		@Override
		public boolean isOppositeOf(PlanTrack track) {
			return track instanceof Track && TRACKS.oppositeRail(this, (Track) track);
		}

		@Override
		public String toString() {
			return from + to + (stopId == 0 ? "" : "#" + stopId + "/" + dwellTime);
		}
	}

	private static final DutyAssembly.Tracks<Track> TRACKS = new DutyAssembly.Tracks<Track>() {
		@Override
		public boolean sameRail(Track track, Track other) {
			return track.from.equals(other.from) && track.to.equals(other.to);
		}

		@Override
		public boolean oppositeRail(Track track, Track other) {
			return track.from.equals(other.to) && track.to.equals(other.from);
		}

		@Override
		public Track withStop(Track track, long stopId, int dwellTime) {
			return new Track(track.from, track.to, stopId, dwellTime);
		}
	};

	private static Track stop(String from, String to, long stopId, int dwellTime) {
		return new Track(from, to, stopId, dwellTime);
	}

	private static Track run(String from, String to) {
		return new Track(from, to, 0, 0);
	}

	private static DutyAssembly.Part<Track, String> part(long routeId, int firstStop, int lastStop, int routeStops, long firstPlatform, long lastPlatform, Track... template) {
		return new DutyAssembly.Part<>(routeId, firstStop, lastStop, routeStops, Arrays.asList(template), "P" + firstPlatform, firstPlatform, "P" + lastPlatform, lastPlatform, 8);
	}

	/**
	 * A host with set parts and set ways between them, which notes what it is asked.
	 */
	private static class FakeHost implements DutyAssembly.Host<Track, String> {

		final List<DutyAssembly.Part<Track, String>> parts = new ArrayList<>();
		List<Track> connection = Collections.emptyList();
		TurnBackSection<Track> section;
		List<Track> lead;
		final List<String> asked = new ArrayList<>();

		@Override
		public DutyAssembly.Part<Track, String> part(int k) {
			return parts.get(k);
		}

		@Override
		public TurnBackSection<Track> connect(List<Track> connectingPath, DutyAssembly.Part<Track, String> before, DutyAssembly.Part<Track, String> after, long routeBeforeId) {
			asked.add("connect " + before.lastPlatformId + ">" + after.firstPlatformId + " route " + routeBeforeId);
			connectingPath.addAll(connection);
			return section;
		}

		@Override
		public void frontTurnBack(List<Track> body, DutyAssembly.Part<Track, String> before, DutyAssembly.Part<Track, String> after, int previousStart, int nextStart, List<TurnBackSection<Track>> turnBackSections) {
			asked.add("front " + previousStart + " " + nextStart);
		}

		@Override
		public List<Track> entryLead(DutyAssembly.Part<Track, String> part) {
			asked.add("lead " + part.routeId);
			return lead;
		}
	}

	private static String names(List<Track> path) {
		return path.stream().map(Track::toString).collect(Collectors.joining(" "));
	}

	@Test
	void aWayToAnotherPlatformJoinsTheTwoAndTheTrainStopsThereForItsDeparture() {
		final FakeHost host = new FakeHost();
		host.parts.add(part(1, 0, 1, 2, 1, 2, stop("a", "b", 1, 10), run("b", "c"), stop("c", "d", 2, 10)));
		final Track nextFirst = stop("f", "g", 3, 0);
		host.parts.add(part(2, 0, 1, 2, 3, 4, nextFirst, run("g", "h"), stop("h", "i", 4, 10)));
		host.connection = Arrays.asList(stop("c", "d", 2, 10), run("d", "e"), run("e", "f"), stop("f", "g", 3, 0));

		final DutyPlan<Track, String> plan = DutyAssembly.assemble("key", 2, false, false, host, TRACKS);
		assertNotNull(plan);
		// The way's first rail is the last stop already, and its last the next first stop, where the train now stops
		assertEquals("ab#1/10 bc cd#2/10 de ef fg#3/8 gh hi#4/10", names(plan.body));
		assertArrayEquals(new int[]{0, 5}, plan.segmentStarts);
		assertArrayEquals(new int[]{0, 3, 5, 8}, plan.indexBoundaries);
		assertEquals(Arrays.asList(1L, 2L), plan.routeIds);
		assertEquals("P1", plan.firstPlatform);
		assertEquals("P4", plan.lastPlatform);
		assertTrue(plan.segmentsKnown);
		// The segment before ran to its end, so it may turn back the way its route lists; the next starts at its first stop
		assertEquals(Arrays.asList("connect 2>3 route 1", "front 0 5"), host.asked);
		// The segment's own path is left as it was
		assertEquals(0, nextFirst.dwellTime);
	}

	@Test
	void turningBackOnThePlatformTheReversedPlatformIsOnlyTheWayOut() {
		final FakeHost host = new FakeHost();
		host.parts.add(part(1, 0, 1, 2, 1, 2, stop("a", "b", 1, 10), stop("b", "c", 2, 10)));
		host.parts.add(part(3, 0, 1, 2, 2, 1, stop("c", "b", 2, 10), stop("b", "a", 1, 10)));
		final DutyPlan<Track, String> plan = DutyAssembly.assemble("key", 2, false, true, host, TRACKS);
		assertEquals("ab#1/10 bc#2/10 cb ba#1/10", names(plan.body));
		assertArrayEquals(new int[]{0, 1}, plan.segmentStarts);
		// The same platform: no way between them is looked for
		assertEquals(Collections.singletonList("front 0 1"), host.asked);
		assertTrue(plan.staysOnLine);
	}

	@Test
	void onlyAChoiceOfWaysRoundFromTheLastStopIsKept() {
		final FakeHost host = new FakeHost();
		host.parts.add(part(1, 0, 1, 2, 1, 2, stop("a", "b", 1, 10), stop("b", "c", 2, 10)));
		host.parts.add(part(2, 0, 1, 2, 3, 4, stop("e", "f", 3, 10), stop("f", "g", 4, 10)));
		host.connection = Arrays.asList(stop("b", "c", 2, 10), run("c", "d"), run("d", "e"), stop("e", "f", 3, 10));
		final List<List<Track>> ways = Arrays.asList(host.connection, host.connection);
		host.section = new TurnBackSection<>(0, ways, 1);
		final DutyPlan<Track, String> plan = DutyAssembly.assemble("key", 2, false, false, host, TRACKS);
		assertEquals(1, plan.turnBackSections.size());
		assertEquals(1, plan.turnBackSections.get(0).start);
		assertEquals(1, plan.turnBackSections.get(0).defaultWay);

		host.section = new TurnBackSection<>(0, Collections.singletonList(host.connection), 0);
		assertTrue(DutyAssembly.assemble("key", 2, false, false, host, TRACKS).turnBackSections.isEmpty());
		// A way that doesn't start at the last stop isn't a way round from it
		host.connection = Arrays.asList(run("x", "e"), stop("e", "f", 3, 10));
		host.section = new TurnBackSection<>(0, Arrays.asList(host.connection, host.connection), 0);
		assertTrue(DutyAssembly.assemble("key", 2, false, false, host, TRACKS).turnBackSections.isEmpty());
	}

	@Test
	void aCutRouteAndOneJoinedPartOfTheWayAreNoted() {
		final FakeHost host = new FakeHost();
		host.lead = Arrays.asList(stop("y", "z", 9, 10), stop("z", "a", 1, 10));
		// Route 1 run from its second stop to its third of four, twice
		host.parts.add(part(1, 1, 2, 4, 1, 2, stop("a", "b", 1, 10), stop("b", "c", 2, 10)));
		host.parts.add(part(1, 1, 2, 4, 2, 1, stop("c", "b", 2, 10), stop("b", "a", 1, 10)));
		final DutyPlan<Track, String> plan = DutyAssembly.assemble("key", 2, false, false, host, TRACKS);
		assertEquals(Collections.singletonMap(1L, 2), plan.cutStops);
		assertEquals(Collections.singletonMap(1L, 1), plan.entryStops);
		assertEquals(host.lead, plan.entryLeads.get(1L));
		assertEquals("{0=2, 1=2}", plan.segmentCuts.toString());
		assertEquals("{0=1, 1=1}", plan.segmentEntries.toString());
		// The lead is found once per route; neither segment ran to the end, so there is no turning back in front
		assertEquals(Collections.singletonList("lead 1"), host.asked.stream().filter(each -> !each.startsWith("connect")).collect(Collectors.toList()));

		// With no lead found, the route isn't noted as joined part of the way, but the segment is
		host.lead = null;
		final DutyPlan<Track, String> noLead = DutyAssembly.assemble("key", 2, false, false, host, TRACKS);
		assertTrue(noLead.entryStops.isEmpty());
		assertEquals("{0=1, 1=1}", noLead.segmentEntries.toString());
	}

	@Test
	void aPartOrAWayNotFoundMeansNoPlan() {
		final FakeHost host = new FakeHost();
		host.parts.add(part(1, 0, 1, 2, 1, 2, stop("a", "b", 1, 10), stop("b", "c", 2, 10)));
		host.parts.add(null);
		assertNull(DutyAssembly.assemble("key", 2, false, false, host, TRACKS));

		host.parts.set(1, part(2, 0, 1, 2, 3, 4, stop("e", "f", 3, 10), stop("f", "g", 4, 10)));
		host.connection = Collections.emptyList();
		assertNull(DutyAssembly.assemble("key", 2, false, false, host, TRACKS));
	}

	@Test
	void aSegmentStartingElsewhereIsAddedAfterTheBody() {
		final FakeHost host = new FakeHost();
		host.parts.add(part(1, 0, 1, 2, 1, 2, stop("a", "b", 1, 10), stop("b", "c", 2, 10)));
		host.parts.add(part(2, 0, 1, 2, 3, 4, stop("e", "f", 3, 10), stop("f", "g", 4, 10)));
		// The way there ends short of the next first stop
		host.connection = Arrays.asList(stop("b", "c", 2, 10), run("c", "d"), run("d", "e"));
		final DutyPlan<Track, String> plan = DutyAssembly.assemble("key", 2, false, false, host, TRACKS);
		assertEquals("ab#1/10 bc#2/10 cd de ef#3/10 fg#4/10", names(plan.body));
		assertArrayEquals(new int[]{0, 4}, plan.segmentStarts);
	}
}
