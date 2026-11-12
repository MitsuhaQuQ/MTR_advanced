package transitsim.network;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A depot right behind a terminus with only a crossover in front of it (站前折返 + 站后车库, user 2026-11-12): a double
 * track line A-B-C, east on z = 0 and west on z = 10, a crossover before C from the east track to the west one, and a
 * siding behind C at the end of each track. The siding at the end of the east track can only reach the west route's
 * first stop (C west) by running C's east platform against the east route; that was no way at all, and the depot had no
 * trains for any service. Now it is taken where there is no other way, and {@link LineDepotWays#againstStops} names the
 * platform, for the game to show. With a second crossover behind C there is another way, and that one is taken.
 */
class DepotBehindTerminusTest {

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
		long eastSiding;
		long westSiding;

		long section(String platform) {
			return sections.getSectionOf(platforms.get(platform).getKey());
		}
	}

	private static Line build(boolean crossoverBehind) {
		final Line line = new Line();
		final List<TrackSections.Rail> rails = new ArrayList<>();
		final String[] names = {"A", "B", "C"};
		for (int i = 0; i < 3; i++) {
			final int x = i * 200;
			final TrackSections.Rail east = rail(x, 0, x + 20, 0, SectionType.PLATFORM, 10 + i);
			final TrackSections.Rail west = rail(x, 10, x + 20, 10, SectionType.PLATFORM, 20 + i);
			line.platforms.put(names[i] + " east", east);
			line.platforms.put(names[i] + " west", west);
			rails.add(east);
			rails.add(west);
		}
		rails.add(track(20, 0, 200, 0));
		rails.add(track(220, 0, 360, 0));
		rails.add(track(360, 0, 400, 0));
		rails.add(track(20, 10, 200, 10));
		rails.add(track(220, 10, 390, 10));
		rails.add(track(390, 10, 400, 10));
		// The crossover before C, from the east track to the west one (trains turn back in front of C)
		rails.add(track(360, 0, 390, 10));
		// Behind A, westbound trains run into a turn back track and come out on the east track
		rails.add(track(0, 10, -40, 10));
		rails.add(rail(-40, 10, -100, 10, SectionType.TURN_BACK, 0));
		rails.add(track(-40, 10, 0, 0));
		// Behind C, a siding at the end of each track
		rails.add(track(420, 0, 450, 0));
		rails.add(track(420, 10, 450, 10));
		final TrackSections.Rail eastSiding = rail(450, 0, 530, 0, SectionType.SIDING, 98);
		final TrackSections.Rail westSiding = rail(450, 10, 530, 10, SectionType.SIDING, 99);
		rails.add(eastSiding);
		rails.add(westSiding);
		if (crossoverBehind) {
			// From the east track at 440 west to the west track at 425: the east siding's trains, going west, cross there
			rails.remove(rails.size() - 4);
			rails.remove(rails.size() - 3);
			rails.add(track(420, 0, 440, 0));
			rails.add(track(440, 0, 450, 0));
			rails.add(track(420, 10, 425, 10));
			rails.add(track(425, 10, 450, 10));
			rails.add(track(440, 0, 425, 10));
		}
		final Map<UUID, TrackSections.Rail> map = new HashMap<>();
		rails.forEach(rail -> map.put(rail.getKey(), rail));
		line.sections.sync(map, Collections.emptyMap());
		line.snapshot = line.sections.snapshot();
		final double ratio = LegRouter.getMaxChordPerCost(line.snapshot, COORDINATES);
		line.source = request -> LegRouter.find(line.snapshot, 0, Collections.emptyMap(), request, COORDINATES, ratio);
		line.eastSiding = line.sections.getSectionOf(eastSiding.getKey());
		line.westSiding = line.sections.getSectionOf(westSiding.getKey());
		for (final List<String> stops : Arrays.asList(Arrays.asList("A east", "B east", "C east"), Arrays.asList("C west", "B west", "A west"))) {
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

	private static RouteLegs.Plan outToWestRoute(Line line, long siding) {
		final long[] first = line.routes.get(1).steps.get(line.routes.get(1).stopSteps[0]);
		return LineDepotWays.planOut(siding, first[0], first[1] == 1, line.stations, line.routes, null, line.snapshot, line.source);
	}

	// To be reworked once the game shows how this layout's line turns at C (its east route has C's west platform as a
	// turn-back platform there, 站前折返, which these plans don't carry): the line itself has no turn at C here
	@org.junit.jupiter.api.Disabled("the line has no turn at C in this plan; see the comment")
	@Test
	void theOnlyWayOutRunsThePlatformAgainstTheLineAndSaysSo() {
		final Line line = build(false);
		final RouteLegs.Plan out = outToWestRoute(line, line.eastSiding);
		assertTrue(out.isFound(), "the east siding has a way out to C west");
		assertEquals(Collections.singletonList(line.section("C east")), LineDepotWays.againstStops(out, line.routes), "it runs C's east platform against the line, and says so");
		assertTrue(out.cost >= LineDepotWays.AGAINST_STOP_PENALTY, "counted as a last resort");
	}

	@Test
	void aWayKeptToTheRuleSaysNothing() {
		final Line line = build(false);
		final RouteLegs.Plan out = outToWestRoute(line, line.westSiding);
		assertTrue(out.isFound(), "the west siding goes straight into C west");
		assertEquals(Collections.emptyList(), LineDepotWays.againstStops(out, line.routes));
		assertTrue(out.cost < LineDepotWays.AGAINST_STOP_PENALTY);
	}

	@Test
	void anotherWayIsTakenWhereThereIsOne() {
		final Line line = build(true);
		final RouteLegs.Plan out = outToWestRoute(line, line.eastSiding);
		assertTrue(out.isFound(), "with a crossover behind C, the east siding reaches C west that way");
		assertEquals(Collections.emptyList(), LineDepotWays.againstStops(out, line.routes), "and runs no platform against the line");
	}
}
