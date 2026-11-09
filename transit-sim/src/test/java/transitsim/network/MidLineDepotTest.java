package transitsim.network;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A depot in the middle of a line (2026-11-09; user: out to the station nearest the depot, then on by the rules, the way
 * the line runs): a double track line A-B-C-D, east on z = 0 and west on z = 10, a siding between B and C south of the
 * east track, joining it facing east; with or without a crossover between the tracks between B and C; turning back beyond
 * both ends, or, as the Q world, at A only on the platform: A is a dead end of the west track, the east route starting
 * there and crossing to the east track beyond it.
 */
class MidLineDepotTest {

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

	/**
	 * The line's routes and the ways out to each route's first stop and back from each's last.
	 */
	private static final class Layout {

		final Map<String, RouteLegs.Plan> ways = new LinkedHashMap<>();
		final List<RouteLegs.Plan> routes = new ArrayList<>();
		final Set<Long> platformSections = new HashSet<>();
		final StringBuilder text = new StringBuilder();
	}

	/**
	 * @param deadEndA whether A is a dead end, the line turning back on the west platform there, as the Q world
	 */
	private static Layout measure(boolean crossover, boolean deadEndA) {
		final List<TrackSections.Rail> rails = new ArrayList<>();
		final String[] names = {"A", "B", "C", "D"};
		final Map<String, TrackSections.Rail> platforms = new LinkedHashMap<>();
		for (int i = 0; i < 4; i++) {
			final int x = i * 200;
			final TrackSections.Rail east = rail(x, 0, x + 20, 0, SectionType.PLATFORM, 10 + i);
			final TrackSections.Rail west = rail(x, 10, x + 20, 10, SectionType.PLATFORM, 20 + i);
			platforms.put(names[i] + " east", east);
			platforms.put(names[i] + " west", west);
			rails.add(east);
			rails.add(west);
		}
		// Plain track between the platforms, with nodes at 40 and 80 for the crossover beyond A, at 250 and 290 for one
		// between B and C, and at 380 where the depot joins
		rails.add(track(20, 0, 80, 0));
		rails.add(track(80, 0, 200, 0));
		rails.add(track(220, 0, 250, 0));
		rails.add(track(250, 0, 290, 0));
		rails.add(track(290, 0, 380, 0));
		rails.add(track(380, 0, 400, 0));
		rails.add(track(420, 0, 600, 0));
		rails.add(track(20, 10, 40, 10));
		rails.add(track(40, 10, 200, 10));
		rails.add(track(220, 10, 250, 10));
		rails.add(track(250, 10, 290, 10));
		rails.add(track(290, 10, 400, 10));
		rails.add(track(420, 10, 600, 10));
		// Turning back beyond each end: a turn back rail joining both tracks; at a dead end A, the crossover beyond it
		if (deadEndA) {
			rails.add(track(40, 10, 80, 0));
		} else {
			rails.add(track(0, 0, -40, 5));
			rails.add(track(0, 10, -40, 5));
			rails.add(rail(-40, 5, -80, 5, SectionType.TURN_BACK, 0));
		}
		rails.add(track(620, 0, 660, 5));
		rails.add(track(620, 10, 660, 5));
		rails.add(rail(660, 5, 700, 5, SectionType.TURN_BACK, 0));
		// The depot: a siding south of the east track, its lead joining the east track at 380 facing east
		final TrackSections.Rail siding = rail(260, -80, 300, -80, SectionType.SIDING, 99);
		rails.add(siding);
		rails.add(track(300, -80, 340, -40));
		rails.add(track(340, -40, 380, 0));
		if (crossover) {
			// From the east track at 250 to the west track at 290, both ways
			rails.add(track(250, 0, 290, 10));
		}
		final Map<UUID, TrackSections.Rail> map = new HashMap<>();
		rails.forEach(rail -> map.put(rail.getKey(), rail));
		final TrackSections sections = new TrackSections();
		sections.sync(map, Collections.emptyMap());
		final TrackSections.Snapshot snapshot = sections.snapshot();
		final double ratio = LegRouter.getMaxChordPerCost(snapshot, COORDINATES);
		final RouteLegs.LegSource source = request -> LegRouter.find(snapshot, 0, Collections.emptyMap(), request, COORDINATES, ratio);
		final long s = sections.getSectionOf(siding.getKey());
		final Layout layout = new Layout();
		layout.text.append(crossover ? "with a crossover" : "without a crossover").append(deadEndA ? ", A a dead end:" : ":");
		// The east route from A, on the west platform at a dead end A, and the west route to A
		final List<String> east = Arrays.asList(deadEndA ? "A west" : "A east", "B east", "C east", "D east");
		final List<String> west = Arrays.asList("D west", "C west", "B west", "A west");
		for (final List<String> stops : Arrays.asList(east, west)) {
			final List<Long> stopSections = new ArrayList<>();
			final List<long[]> waypoints = new ArrayList<>();
			stops.forEach(stop -> {
				stopSections.add(sections.getSectionOf(platforms.get(stop).getKey()));
				waypoints.add(new long[0]);
			});
			final RouteLegs.Plan route = RouteLegs.plan(stopSections, waypoints, null, source);
			assertTrue(route.isFound(), "route " + stops);
			layout.routes.add(route);
		}
		platforms.values().forEach(platform -> layout.platformSections.add(sections.getSectionOf(platform.getKey())));
		// The stations a train may go out and back by: all of them, the nearest found among them
		final List<Long> stations = new ArrayList<>(layout.platformSections);
		for (final RouteLegs.Plan route : layout.routes) {
			final long[] first = route.steps.get(route.stopSteps[0]);
			final long[] last = route.steps.get(route.stopSteps[route.stopSteps.length - 1]);
			final RouteLegs.Plan out = LineDepotWays.planOut(s, first[0], first[1] == 1, stations, layout.routes, null, snapshot, source);
			final RouteLegs.Plan back = LineDepotWays.planBack(last[0], last[1] == 1, s, stations, layout.routes, null, snapshot, source);
			final String outName = "out to " + nameOf(platforms, sections, first[0]);
			final String backName = "back from " + nameOf(platforms, sections, last[0]);
			layout.ways.put(outName, out);
			layout.ways.put(backName, back);
			layout.text.append(String.format("%n  %s: %s%n  %s: %s", outName, describe(out, platforms, sections), backName, describe(back, platforms, sections)));
		}
		return layout;
	}

	private static String nameOf(Map<String, TrackSections.Rail> platforms, TrackSections sections, long section) {
		for (final Map.Entry<String, TrackSections.Rail> entry : platforms.entrySet()) {
			if (sections.getSectionOf(entry.getValue().getKey()) == section) {
				return entry.getKey();
			}
		}
		return null;
	}

	private static String describe(RouteLegs.Plan plan, Map<String, TrackSections.Rail> platforms, TrackSections sections) {
		if (!plan.isFound()) {
			return "none";
		}
		final StringBuilder text = new StringBuilder(String.format("cost %.0f, stops %d, by", plan.cost, plan.stopSteps.length));
		long last = 0;
		for (final long[] step : plan.steps) {
			final String name = nameOf(platforms, sections, step[0]);
			if (name != null && step[0] != last) {
				text.append(' ').append(name).append(step[1] == 1 ? '>' : '<');
			}
			if (step[2] == 1) {
				text.append(" turn");
			}
			last = step[0];
		}
		return text.toString();
	}

	/**
	 * @return the first step of the way out that is one of the platforms, the station it goes out by, or of the way back the
	 * last, the station it goes back by
	 */
	private static int stationStep(RouteLegs.Plan plan, Set<Long> platformSections, boolean out) {
		for (int i = 0; i < plan.steps.size(); i++) {
			final int step = out ? i : plan.steps.size() - 1 - i;
			if (platformSections.contains(plan.steps.get(step)[0])) {
				return step;
			}
		}
		return -1;
	}

	/**
	 * Every section of the routes run between the station and the platform of the line is run the way a route runs it.
	 */
	private static void assertAlongTheLine(Layout layout) {
		final Map<Long, Set<Long>> lineWays = new HashMap<>();
		layout.routes.forEach(route -> route.steps.forEach(step -> lineWays.computeIfAbsent(step[0], key -> new HashSet<>()).add(step[1])));
		layout.ways.forEach((name, plan) -> {
			assertTrue(plan.isFound(), name);
			final boolean out = name.startsWith("out");
			final int station = stationStep(plan, layout.platformSections, out);
			assertTrue(station >= 0, name);
			for (int i = out ? station + 1 : 0; i < (out ? plan.steps.size() : station); i++) {
				final long[] step = plan.steps.get(i);
				final Set<Long> ways = lineWays.get(step[0]);
				assertTrue(ways == null || ways.contains(step[1]), name + ": step " + i + " runs a section of the line against it");
			}
		});
	}

	@Test
	void waysRunTheLinesWayFromTheNearestStation() {
		for (final boolean crossover : new boolean[]{false, true}) {
			final Layout layout = measure(crossover, false);
			System.out.println("MIDLINE " + layout.text);
			assertAlongTheLine(layout);
		}
	}

	@Test
	void aDeadEndTerminusIsTurnedBackAtOnItsPlatform() {
		// As the Q world: out to the east route's first stop, the dead end A, the train goes on along the line from the
		// nearest station and turns back on A's platform where the west route ends, rather than run the west track east
		for (final boolean crossover : new boolean[]{false, true}) {
			final Layout layout = measure(crossover, true);
			System.out.println("MIDLINE " + layout.text);
			assertAlongTheLine(layout);
			final RouteLegs.Plan out = layout.ways.get("out to A west");
			final long[] last = out.steps.get(out.steps.size() - 1);
			assertEquals(1, last[2], "turned back on the platform at the end");
			assertTrue(out.stopSteps.length >= 3, "the platform turned back at is a stop before the last");
		}
	}
}
