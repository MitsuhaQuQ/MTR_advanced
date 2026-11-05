package transitsim.network;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Up and down by where a route's ends lie, main and passing tracks, and the order across a station (L2).
 */
class StationTracksTest {

	/**
	 * Two stations 200 blocks apart east to west, A at x 0 and B at x 200, each with a track each way: 1 on the north side
	 * for trains running east, 2 on the south side for trains running west.
	 */
	private static List<StationTracks.PlatformInput> eastWest() {
		return Arrays.asList(
				platform(11, 1, "1", false, 0, -5, 20, -5),
				platform(12, 1, "2", false, 0, 5, 20, 5),
				platform(21, 2, "1", false, 200, -5, 220, -5),
				platform(22, 2, "2", false, 200, 5, 220, 5)
		);
	}

	@Test
	void eastIsUpAndWestIsDown() {
		final Map<Long, StationTracks.Track> tracks = StationTracks.compute(eastWest(), Arrays.asList(route(stop(11, 0), stop(21, 0)), route(stop(22, 1), stop(12, 1))));
		assertEquals(StationTracks.UP, tracks.get(11L).direction);
		assertEquals(StationTracks.UP, tracks.get(21L).direction);
		assertEquals(StationTracks.DOWN, tracks.get(12L).direction);
		assertEquals(StationTracks.DOWN, tracks.get(22L).direction);
	}

	@Test
	void northIsUp() {
		final List<StationTracks.PlatformInput> platforms = Arrays.asList(platform(11, 1, "1", false, 0, 0, 0, -20), platform(21, 2, "1", false, 0, -300, 0, -320));
		final Map<Long, StationTracks.Track> tracks = StationTracks.compute(platforms, Collections.singletonList(route(stop(11, 0), stop(21, 0))));
		assertEquals(StationTracks.UP, tracks.get(11L).direction);
		final Map<Long, StationTracks.Track> back = StationTracks.compute(platforms, Collections.singletonList(route(stop(21, 1), stop(11, 1))));
		assertEquals(StationTracks.DOWN, back.get(11L).direction);
	}

	@Test
	void aLoopTakesTheWayAlongEachPlatform() {
		// Out east along 11, back west along 12, ending where it started
		final Map<Long, StationTracks.Track> tracks = StationTracks.compute(eastWest(), Collections.singletonList(route(stop(11, 0), stop(21, 0), stop(22, 1), stop(12, 1), stop(11, 0))));
		assertEquals(StationTracks.UP, tracks.get(11L).direction);
		assertEquals(StationTracks.DOWN, tracks.get(22L).direction);
	}

	@Test
	void aTrackBothDirectionsUseIsBoth() {
		final Map<Long, StationTracks.Track> tracks = StationTracks.compute(eastWest(), Arrays.asList(route(stop(11, 0), stop(21, 0)), route(stop(21, 1), stop(11, 1))));
		assertEquals(StationTracks.BOTH, tracks.get(11L).direction);
		assertEquals("", tracks.get(12L).direction);
	}

	@Test
	void theTrackExpressesPassOnIsTheMainLine() {
		// Station 2 gets a second up track, 23, where locals stop while expresses pass on 21
		final List<StationTracks.PlatformInput> platforms = new java.util.ArrayList<>(eastWest());
		platforms.add(platform(23, 2, "3", false, 200, -10, 220, -10));
		platforms.add(platform(31, 3, "1", false, 400, -5, 420, -5));
		final StationTracks.RouteInput express = route(stop(11, 0), stop(21, 0), stop(31, 0));
		express.stops.set(1, new StationTracks.StopInput(21, 0, true));
		final StationTracks.RouteInput local = route(stop(11, 0), stop(23, 0), stop(31, 0));
		final Map<Long, StationTracks.Track> tracks = StationTracks.compute(platforms, Arrays.asList(express, local));
		assertEquals(StationTracks.MAIN, tracks.get(21L).kind);
		assertEquals(StationTracks.PASSING, tracks.get(23L).kind);

		// Without services passing it can't be told
		final Map<Long, StationTracks.Track> unknown = StationTracks.compute(platforms, Arrays.asList(route(stop(11, 0), stop(21, 0), stop(31, 0)), local));
		assertEquals("", unknown.get(21L).kind);
		assertEquals("", unknown.get(23L).kind);
	}

	@Test
	void theHandSetFlagWins() {
		final List<StationTracks.PlatformInput> platforms = Arrays.asList(platform(11, 1, "1", true, 0, -5, 20, -5), platform(21, 2, "1", false, 200, -5, 220, -5));
		final Map<Long, StationTracks.Track> tracks = StationTracks.compute(platforms, Collections.singletonList(route(stop(11, 0), stop(21, 0))));
		assertEquals(StationTracks.PASSING, tracks.get(11L).kind);
	}

	@Test
	void tracksAreOrderedAcrossTheStation() {
		// Looking up the line, east, the north track 1 is on the left
		final Map<Long, StationTracks.Track> tracks = StationTracks.compute(eastWest(), Collections.emptyList());
		assertTrue(tracks.get(11L).order < tracks.get(12L).order);
		assertEquals(0, tracks.get(21L).order);
		assertEquals(1, tracks.get(22L).order);
	}

	private static StationTracks.PlatformInput platform(long id, long station, String name, boolean passing, double x1, double z1, double x2, double z2) {
		return new StationTracks.PlatformInput(id, station, 1, name, passing, x1, z1, x2, z2);
	}

	private static StationTracks.StopInput stop(long platformId, int way) {
		return new StationTracks.StopInput(platformId, way, false);
	}

	private static StationTracks.RouteInput route(StationTracks.StopInput... stops) {
		final StationTracks.RouteInput route = new StationTracks.RouteInput();
		route.stops.addAll(Arrays.asList(stops));
		return route;
	}
}
