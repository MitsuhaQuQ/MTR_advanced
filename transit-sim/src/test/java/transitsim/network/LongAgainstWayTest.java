package transitsim.network;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A depot joined to one track only, facing the other way to it (line 6 of the six test lines, 2026-11-14, item E of
 * MTR-待验证.md): a double track line A-B-C-D, east on z = 0 and west on z = 10, a dead-end turn back track behind A
 * coming out onto both tracks, and a siding joined to the west track at x 600, between C and D, facing east. Without a
 * crossover the only way back from A runs the whole west track against the line, which {@link LineDepotWays#againstLength}
 * measures and the depot's hints tell of; with a crossover at C from the east track to the west one (at the station
 * nearest the entrance, as depots usually are, user 2026-11-14) the way back from C runs only the short stretch from there.
 */
class LongAgainstWayTest {

	private static final LegRouter.Coordinates COORDINATES = new LegRouter.Coordinates() {
		@Override
		public int getX(long node) {
			return (int) (node / 1000000L - 100000);
		}

		@Override
		public int getZ(long node) {
			return (int) (node % 1000000L - 100000);
		}
	};

	private static long pos(int x, int z) {
		return (x + 100000L) * 1000000L + z + 100000L;
	}

	private static TrackSections.Rail rail(int x1, int z1, int x2, int z2, SectionType type, long savedRailId) {
		final int facing1 = (int) Math.round(Math.toDegrees(Math.atan2(z2 - z1, x2 - x1)) * 10);
		final int facing2 = (int) Math.round(Math.toDegrees(Math.atan2(z1 - z2, x1 - x2)) * 10);
		final long node1 = pos(x1, z1);
		final long node2 = pos(x2, z2);
		final boolean firstLower = node1 < node2;
		final long shape = firstLower ? (long) facing1 << 32 | facing2 & 0xFFFFFFFFL : (long) facing2 << 32 | facing1 & 0xFFFFFFFFL;
		return new TrackSections.Rail(node1, node2, Math.hypot(x2 - x1, z2 - z1), shape, type == SectionType.PLATFORM ? 80 : 120, type == SectionType.NORMAL, type, true, true, savedRailId);
	}

	private static TrackSections.Rail track(int x1, int z1, int x2, int z2) {
		return rail(x1, z1, x2, z2, SectionType.NORMAL, 0);
	}

	private static final class Line {
		final TrackSections sections = new TrackSections();
		TrackSections.Snapshot snapshot;
		RouteLegs.LegSource source;
		final Map<String, TrackSections.Rail> platforms = new LinkedHashMap<>();
		final List<RouteLegs.Plan> routes = new ArrayList<>();
		final Set<Long> stations = new HashSet<>();
		long siding;

		long section(String platform) {
			return sections.getSectionOf(platforms.get(platform).getKey());
		}

		String name(long section) {
			for (final Map.Entry<String, TrackSections.Rail> entry : platforms.entrySet()) {
				if (sections.getSectionOf(entry.getValue().getKey()) == section) {
					return entry.getKey();
				}
			}
			return String.valueOf(section);
		}

		boolean along(String platform, int route) {
			for (final long[] step : routes.get(route).steps) {
				if (step[0] == section(platform)) {
					return step[1] == 1;
				}
			}
			throw new IllegalArgumentException(platform);
		}
	}

	private static Line build(boolean crossover) {
		final Line line = new Line();
		final List<TrackSections.Rail> rails = new ArrayList<>();
		final String[] names = {"A", "B", "C", "D"};
		final int[] xs = {0, 200, 400, 800};
		for (int i = 0; i < 4; i++) {
			final TrackSections.Rail east = rail(xs[i], 0, xs[i] + 20, 0, SectionType.PLATFORM, 10 + i);
			final TrackSections.Rail west = rail(xs[i], 10, xs[i] + 20, 10, SectionType.PLATFORM, 20 + i);
			line.platforms.put(names[i] + " east", east);
			line.platforms.put(names[i] + " west", west);
			rails.add(east);
			rails.add(west);
		}
		rails.add(track(20, 0, 200, 0));
		rails.add(track(220, 0, 400, 0));
		rails.add(track(420, 0, 800, 0));
		rails.add(track(20, 10, 200, 10));
		rails.add(track(220, 10, 400, 10));
		rails.add(track(420, 10, 450, 10));
		rails.add(track(450, 10, 600, 10));
		rails.add(track(600, 10, 800, 10));
		// Behind A, a dead end coming out onto both tracks
		rails.add(track(0, 10, -40, 10));
		rails.add(rail(-40, 10, -100, 10, SectionType.TURN_BACK, 0));
		rails.add(track(-40, 10, 0, 0));
		// Behind D, east trains cross to the west track
		rails.add(track(820, 0, 860, 10));
		rails.add(track(820, 10, 860, 10));
		rails.add(rail(860, 10, 920, 10, SectionType.TURN_BACK, 0));
		// The siding, joined to the west track at 600 facing east
		rails.add(track(600, 10, 640, 20));
		final TrackSections.Rail siding = rail(640, 20, 720, 20, SectionType.SIDING, 99);
		rails.add(siding);
		if (crossover) {
			rails.add(track(420, 0, 450, 10));
		}
		final Map<UUID, TrackSections.Rail> map = new HashMap<>();
		rails.forEach(rail -> map.put(rail.getKey(), rail));
		line.sections.sync(map, Collections.emptyMap());
		line.snapshot = line.sections.snapshot();
		final double ratio = LegRouter.getMaxChordPerCost(line.snapshot, COORDINATES);
		line.source = request -> LegRouter.find(line.snapshot, 0, Collections.emptyMap(), request, COORDINATES, ratio);
		line.siding = line.sections.getSectionOf(siding.getKey());
		for (final List<String> stops : Arrays.asList(Arrays.asList("A east", "B east", "C east", "D east"), Arrays.asList("D west", "C west", "B west", "A west"))) {
			final List<Long> stopSections = new ArrayList<>();
			final List<long[]> waypoints = new ArrayList<>();
			stops.forEach(stop -> {
				stopSections.add(line.section(stop));
				waypoints.add(new long[0]);
				line.stations.add(line.section(stop));
			});
			final RouteLegs.Plan route = RouteLegs.plan(stopSections, waypoints, null, line.source);
			assertTrue(route.isFound(), "route " + stops);
			line.routes.add(route);
		}
		return line;
	}

	@Test
	void backFromTheFirstStationRunsTheWholeTrackAgainstTheLine() {
		final Line line = build(false);
		final RouteLegs.Plan back = RouteLegs.planBetween(line.section("A west"), line.along("A west", 1), line.siding, null, line.source);
		assertTrue(back.isFound(), "turning behind A, the west track leads east to the siding");
		final double length = LineDepotWays.againstLength(back, line.routes, line.snapshot);
		assertTrue(length > 550 && length < 650, "the whole west track from A to the siding, B and C's platforms too: " + length);
		assertTrue(length > LineDepotWays.LONG_AGAINST);
		final List<String> spans = new ArrayList<>();
		LineDepotWays.againstSpans(back, line.routes).forEach(span -> spans.add(line.name(span[0]) + "-" + line.name(span[1])));
		assertEquals(Arrays.asList("C west-B west", "B west-A west"), spans, "from A to C all the way against the west route; C to D only part of the way");
	}

	@Test
	void aCrossoverAtTheNearestStationKeepsItShort() {
		final Line line = build(true);
		final RouteLegs.Plan back = LineDepotWays.planBack(line.section("C east"), line.along("C east", 0), line.siding, line.stations, line.routes, null, line.snapshot, line.source);
		assertTrue(back.isFound(), "from C's east platform over the crossover to the siding");
		assertEquals(Collections.emptyList(), LineDepotWays.againstStops(back, line.routes));
		final double length = LineDepotWays.againstLength(back, line.routes, line.snapshot);
		assertEquals(150, length, 1, "only the west track from the crossover to the siding; the crossover isn't the line's");
		assertTrue(length < LineDepotWays.LONG_AGAINST);
		assertEquals(Collections.emptyList(), LineDepotWays.againstSpans(back, line.routes), "not all of D to C");
	}

	@Test
	void aWayOutTheLinesWayRunsNothingAgainstIt() {
		final Line line = build(true);
		final RouteLegs.Plan out = RouteLegs.planBetween(line.siding, null, line.section("C west"), line.along("C west", 1), line.source);
		assertTrue(out.isFound());
		assertEquals(0, LineDepotWays.againstLength(out, line.routes, line.snapshot));
		assertEquals(Collections.emptyList(), LineDepotWays.againstSpans(out, line.routes));
	}
}
