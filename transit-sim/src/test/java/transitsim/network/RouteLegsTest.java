package transitsim.network;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Routes made of legs found on the track sections (stages document round 2): the same cost as searching the whole route
 * rail by rail; a new shortcut finds the legs it changes again; routes keep to the track their line owns; legs kept
 * across changes give what finding everything again gives, whatever the threads.
 */
class RouteLegsTest {

	/**
	 * Positions as {@link LegalNetworks} makes them.
	 */
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

	/**
	 * A rail between two positions, its facings the way it points at each end, in tenths of a degree.
	 */
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

	private static Map<UUID, TrackSections.Rail> railMap(TrackSections.Rail... rails) {
		final Map<UUID, TrackSections.Rail> map = new HashMap<>();
		for (final TrackSections.Rail rail : rails) {
			map.put(rail.getKey(), rail);
		}
		return map;
	}

	private static RouteLegs.LegSource fresh(TrackSections.Snapshot snapshot, long version, Map<Long, Set<String>> owners) {
		final double ratio = LegRouter.getMaxChordPerCost(snapshot, COORDINATES);
		return request -> LegRouter.find(snapshot, version, owners, request, COORDINATES, ratio);
	}

	private static RouteLegs.LegSource cached(LegCache cache, TrackSections.Snapshot snapshot, Map<Long, Set<String>> owners, int[] found) {
		final double ratio = LegRouter.getMaxChordPerCost(snapshot, COORDINATES);
		return request -> {
			final LegRouter.Leg kept = cache.get(request);
			if (kept != null) {
				return kept;
			}
			found[0]++;
			final LegRouter.Leg leg = LegRouter.find(snapshot, cache.getVersion(), owners, request, COORDINATES, ratio);
			final LegRouter.Leg taken = cache.keep(leg);
			return taken == null ? leg : taken;
		};
	}

	private static Map.Entry<List<Long>, List<long[]>> stopsOf(LegalNetworks.GeneratedRoute route, TrackSections.Snapshot snapshot) {
		final List<Long> stops = new ArrayList<>();
		final List<long[]> waypoints = new ArrayList<>();
		List<Long> pending = new ArrayList<>();
		for (final RailRouter.Target target : route.targets) {
			if (target.isRail()) {
				if (!stops.isEmpty()) {
					waypoints.add(pending.stream().mapToLong(Long::longValue).toArray());
				}
				pending = new ArrayList<>();
				final TrackSections.Section section = snapshot.getSectionOf(target.getPos1(), target.getPos2());
				stops.add(section == null ? 0 : section.hash);
			} else {
				pending.add(target.getPos1());
			}
		}
		return new AbstractMap.SimpleEntry<>(stops, waypoints);
	}

	@Test
	void legsCostWhatSearchingTheWholeRouteCosts() {
		int compared = 0;
		for (long seed = 0; seed < 30; seed++) {
			final LegalNetworks network = LegalNetworks.generate(seed);
			final TrackSections sections = new TrackSections();
			// The generator costs a rail as its length, which a section costs at 72 km/h, a block a tick
			sections.sync(TrackSectionsTest.railsOf(network, 72), Collections.emptyMap());
			final TrackSections.Snapshot snapshot = sections.snapshot();
			final RouteLegs.LegSource legs = fresh(snapshot, 0, Collections.emptyMap());
			for (final LegalNetworks.GeneratedRoute route : network.routes) {
				final RailRouter.Result<String> whole = RailRouter.route(network.graph(), route.targets);
				final Map.Entry<List<Long>, List<long[]>> stops = stopsOf(route, snapshot);
				final RouteLegs.Plan plan = RouteLegs.plan(stops.getKey(), stops.getValue(), null, legs);
				final RailRouter.Target first = route.targets.get(0);
				if (!whole.isSuccess() && (network.graph().find(first.getPos1(), first.getPos2()) == null || network.graph().find(first.getPos2(), first.getPos1()) == null)) {
					// RailRouter needs the first stop laid both ways, as MTR lays every rail (a one way rail's way back is
					// there, disabled); the generator lays one way lines only one way
					continue;
				}
				assertEquals(whole.isSuccess(), plan.isFound(), "seed " + seed + " " + route);
				if (whole.isSuccess()) {
					final double wholeCost = whole.steps.get(whole.steps.size() - 1).cost;
					assertEquals(wholeCost, plan.cost, Math.max(1E-4, wholeCost * 1E-5), "seed " + seed + " " + route);
					compared++;
				}
			}
		}
		assertTrue(compared > 100, "compared " + compared);
	}

	@Test
	void aWayToASidingIsDoneOnceTheSidingIsReached() {
		// Platform P, track east to a siding at the end of the line; west of P a long line with a spur at every node
		final TrackSections.Rail platform = rail(0, 0, 20, 0, SectionType.PLATFORM, 1);
		final TrackSections.Rail siding = rail(60, 0, 100, 0, SectionType.SIDING, 9);
		final List<TrackSections.Rail> all = new ArrayList<>(Arrays.asList(platform, siding, track(20, 0, 60, 0)));
		for (int i = 0; i < 50; i++) {
			all.add(track(-i * 20, 0, -(i + 1) * 20, 0));
			all.add(track(-(i + 1) * 20, 0, -(i + 1) * 20 - 10, 40));
		}
		final TrackSections sections = new TrackSections();
		sections.sync(railMap(all.toArray(new TrackSections.Rail[0])), Collections.emptyMap());
		final TrackSections.Snapshot snapshot = sections.snapshot();
		final long from = sections.getSectionOf(platform.getKey());
		final long to = sections.getSectionOf(siding.getKey());
		final LegRouter.Leg leg = fresh(snapshot, 0, Collections.emptyMap()).get(new LegRouter.Request(from, true, to, new long[0], null));
		// Found the way it can be reached, and not the line west of the platform, which the other way would have searched
		assertTrue(Double.isFinite(Math.min(leg.getCost(true), leg.getCost(false))));
		assertTrue(leg.domainSections.length < 10, leg.domainSections.length + " of " + snapshot.sections.size());
		assertTrue(RouteLegs.plan(Arrays.asList(from, to), Collections.emptyList(), null, fresh(snapshot, 0, Collections.emptyMap())).isFound());
	}

	@Test
	void aLegToATerminusDoesntSearchForTheWayThatEndsThere() throws java.io.IOException {
		// A terminus platform at the end of the track, reached from the east; west of the first platform a long line with
		// a spur at every node, which looking for the terminus's other way would have searched
		final TrackSections.Rail platform = rail(0, 0, 20, 0, SectionType.PLATFORM, 1);
		final TrackSections.Rail terminus = rail(100, 0, 120, 0, SectionType.PLATFORM, 2);
		final List<TrackSections.Rail> all = new ArrayList<>(Arrays.asList(platform, terminus, track(20, 0, 100, 0)));
		for (int i = 0; i < 50; i++) {
			all.add(track(-i * 20, 0, -(i + 1) * 20, 0));
			all.add(track(-(i + 1) * 20, 0, -(i + 1) * 20 - 10, 40));
		}
		final TrackSections sections = new TrackSections();
		sections.sync(railMap(all.toArray(new TrackSections.Rail[0])), Collections.emptyMap());
		final TrackSections.Snapshot snapshot = sections.snapshot();
		final long from = sections.getSectionOf(platform.getKey());
		final long to = sections.getSectionOf(terminus.getKey());
		final LegRouter.Leg leg = fresh(snapshot, 0, Collections.emptyMap()).get(new LegRouter.Request(from, true, to, new long[0], "A"));
		assertTrue(Double.isFinite(Math.min(leg.getCost(true), leg.getCost(false))));
		assertTrue(leg.domainSections.length < 10, leg.domainSections.length + " of " + snapshot.sections.size());
		// Saved and read back as it was, the way it may start too
		final LegRouter.Leg strict = fresh(snapshot, 0, Collections.emptyMap()).get(new LegRouter.Request(from, true, to, new long[0], null, false));
		final LegRouter.Leg read = LegRouter.Leg.fromBytes(strict.toBytes(), 0);
		assertNotNull(read);
		assertEquals(strict.request.key, read.request.key);
		assertFalse(read.request.turnAtStart);
	}

	@Test
	void theOtherWayIsNotLookedForBeyondWhereItCouldMatter() {
		// Beyond the last stop a short track to the end of the line, so the stop could be entered from there but can't be
		// reached that way; behind the first stop a long line with a spur at every node, which a train turning back at once
		// could search; a connecting line, which may run anywhere
		final TrackSections.Rail platform = rail(0, 0, 20, 0, SectionType.PLATFORM, 1);
		final TrackSections.Rail stop = rail(100, 0, 120, 0, SectionType.PLATFORM, 2);
		final List<TrackSections.Rail> all = new ArrayList<>(Arrays.asList(platform, stop, track(20, 0, 100, 0), track(120, 0, 160, 0)));
		for (int i = 0; i < 50; i++) {
			all.add(track(-i * 20, 0, -(i + 1) * 20, 0));
			all.add(track(-(i + 1) * 20, 0, -(i + 1) * 20 - 10, 40));
		}
		final TrackSections sections = new TrackSections();
		sections.sync(railMap(all.toArray(new TrackSections.Rail[0])), Collections.emptyMap());
		final TrackSections.Snapshot snapshot = sections.snapshot();
		final long from = sections.getSectionOf(platform.getKey());
		final long to = sections.getSectionOf(stop.getKey());
		final boolean eastIsAlong = sections.getSection(from).getNodes()[1] == pos(20, 0);
		final LegRouter.Leg leg = fresh(snapshot, 0, Collections.emptyMap()).get(new LegRouter.Request(from, eastIsAlong, to, new long[0], null));
		assertTrue(Double.isFinite(Math.min(leg.getCost(true), leg.getCost(false))));
		assertTrue(leg.domainSections.length < 20, leg.domainSections.length + " of " + snapshot.sections.size());
	}

	@Test
	void waysOutAndBackRunThePlatformTheWayTheLineDoes() {
		// A siding at each end of a platform
		final TrackSections.Rail platform = rail(0, 0, 20, 0, SectionType.PLATFORM, 1);
		final TrackSections.Rail east = rail(60, 0, 100, 0, SectionType.SIDING, 8);
		final TrackSections.Rail west = rail(-40, 0, -80, 0, SectionType.SIDING, 9);
		final TrackSections sections = new TrackSections();
		sections.sync(railMap(platform, east, west, track(20, 0, 60, 0), track(0, 0, -40, 0)), Collections.emptyMap());
		final TrackSections.Snapshot snapshot = sections.snapshot();
		final long p = sections.getSectionOf(platform.getKey());
		final long e = sections.getSectionOf(east.getKey());
		final long w = sections.getSectionOf(west.getKey());
		final boolean eastIsAlong = sections.getSection(p).getNodes()[1] == pos(20, 0);
		final RouteLegs.LegSource source = fresh(snapshot, 0, Collections.emptyMap());
		// Back from a line running east: on to the east siding, never turning back on the platform for the west one
		assertTrue(RouteLegs.planBetween(p, eastIsAlong, e, null, source).isFound());
		assertFalse(RouteLegs.planBetween(p, eastIsAlong, w, null, source).isFound());
		assertTrue(RouteLegs.planBetween(p, !eastIsAlong, w, null, source).isFound());
		// A route's leg may turn back at once at a cost, as before
		assertTrue(RouteLegs.plan(Arrays.asList(p, w), Collections.emptyList(), null, source).isFound());
		// Out to a line running east: from the west siding, arriving the line's way; from the east one it can't
		assertTrue(RouteLegs.planBetween(w, null, p, eastIsAlong, source).isFound());
		assertFalse(RouteLegs.planBetween(e, null, p, eastIsAlong, source).isFound());
	}

	@Test
	void aNewShortcutFindsTheLegAgain() {
		// Platform A, a way round by C, platform B; then a straight way from A to B
		final TrackSections.Rail platformA = rail(0, 0, 20, 0, SectionType.PLATFORM, 1);
		final TrackSections.Rail platformB = rail(220, 0, 240, 0, SectionType.PLATFORM, 2);
		final Map<UUID, TrackSections.Rail> rails = railMap(platformA, platformB, track(20, 0, 120, 100), track(120, 100, 220, 0));
		final TrackSections sections = new TrackSections();
		sections.sync(rails, Collections.emptyMap());
		final LegCache cache = new LegCache();
		final int[] found = {0};
		final List<Long> stops = Arrays.asList(platformA.getKey(), platformB.getKey()).stream().map(sections::getSectionOf).collect(java.util.stream.Collectors.toList());
		final RouteLegs.Plan before = RouteLegs.plan(stops, Collections.emptyList(), "A", cached(cache, sections.snapshot(), Collections.emptyMap(), found));
		assertTrue(before.isFound());
		// The leg from each way the first stop can be run
		final int foundBefore = found[0];
		assertEquals(2, foundBefore);

		// The new rail touches none of the sections of the old way, but joins at a node the search went on from
		final TrackSections.Rail shortcut = track(20, 0, 220, 0);
		rails.put(shortcut.getKey(), shortcut);
		final TrackSections.Change change = sections.sync(rails, Collections.emptyMap());
		final Set<Long> changed = new HashSet<>(change.added);
		changed.addAll(change.removed);
		final Set<Long> joined = new HashSet<>();
		change.added.forEach(hash -> {
			joined.add(sections.getSection(hash).getStart());
			joined.add(sections.getSection(hash).getEnd());
		});
		assertFalse(cache.change(changed, joined).isEmpty());
		final RouteLegs.Plan after = RouteLegs.plan(stops, Collections.emptyList(), "A", cached(cache, sections.snapshot(), Collections.emptyMap(), found));
		assertTrue(found[0] > foundBefore, "found again");
		assertTrue(after.cost < before.cost);
		assertTrue(after.steps.stream().anyMatch(step -> step[0] == sections.getSectionOf(shortcut.getKey())));
	}

	@Test
	void routesKeepToTheTrackTheirLineOwns() {
		// Line X runs a long way round from X1 to X2; line Y runs straight beside it; crossovers join them at both ends
		final TrackSections.Rail x1 = rail(0, 0, 20, 0, SectionType.PLATFORM, 1);
		final TrackSections.Rail x2 = rail(220, 0, 240, 0, SectionType.PLATFORM, 2);
		final TrackSections.Rail y1 = rail(0, 10, 20, 10, SectionType.PLATFORM, 3);
		final TrackSections.Rail y2 = rail(220, 10, 240, 10, SectionType.PLATFORM, 4);
		final TrackSections.Rail crossover1 = track(40, 0, 60, 10);
		final TrackSections.Rail crossover2 = track(180, 10, 200, 0);
		final Map<UUID, TrackSections.Rail> rails = railMap(x1, x2, y1, y2, crossover1, crossover2,
				track(20, 0, 40, 0), track(40, 0, 120, -200), track(120, -200, 200, 0), track(200, 0, 220, 0),
				track(20, 10, 60, 10), track(60, 10, 180, 10), track(180, 10, 220, 10));
		final Map<Long, Integer> marks = new HashMap<>();
		// The crossovers are boundary rails: their ends cut
		for (final TrackSections.Rail crossover : new TrackSections.Rail[]{crossover1, crossover2}) {
			marks.put(crossover.node1, TrackSections.MARK_BOUNDARY_RAIL);
			marks.put(crossover.node2, TrackSections.MARK_BOUNDARY_RAIL);
		}
		final TrackSections sections = new TrackSections();
		sections.sync(rails, marks);
		final TrackSections.Snapshot snapshot = sections.snapshot();
		final Map<Long, List<String>> platformLines = new HashMap<>();
		platformLines.put(1L, Collections.singletonList("X"));
		platformLines.put(2L, Collections.singletonList("X"));
		platformLines.put(3L, Collections.singletonList("Y"));
		platformLines.put(4L, Collections.singletonList("Y"));
		final Set<UUID> boundaryRails = new HashSet<>(Arrays.asList(crossover1.getKey(), crossover2.getKey()));
		final Map<Long, Set<String>> owned = TrackOwnership.compute(snapshot, Collections.emptySet(), boundaryRails, platformLines, Collections.emptyMap());
		assertEquals(new TreeSet<>(Arrays.asList("X", "Y")), owned.get(sections.getSectionOf(crossover1.getKey())));

		final List<Long> stops = Arrays.asList(sections.getSectionOf(x1.getKey()), sections.getSectionOf(x2.getKey()));
		final LegCache cache = new LegCache();
		final int[] found = {0};
		final RouteLegs.Plan kept = RouteLegs.plan(stops, Collections.emptyList(), "X", cached(cache, snapshot, owned, found));
		assertTrue(kept.isFound());
		final long yTrack = sections.getSectionOf(RailKeys.of(pos(60, 10), pos(180, 10)));
		assertTrue(kept.steps.stream().noneMatch(step -> step[0] == yTrack), "line X kept off line Y's track");
		// Unrestricted, the way over Y is shorter
		final RouteLegs.Plan anywhere = RouteLegs.plan(stops, Collections.emptyList(), null, fresh(snapshot, 0, owned));
		assertTrue(anywhere.cost < kept.cost);

		// Without the boundaries the lines own each other's track: only legs that touched sections whose owners changed
		final Map<Long, Set<String>> merged = TrackOwnership.compute(snapshot, Collections.emptySet(), Collections.emptySet(), platformLines, Collections.emptyMap());
		final Set<Long> changed = TrackOwnership.changed(owned, merged);
		assertTrue(changed.contains(yTrack));
		// A connecting line's leg, which runs anywhere, is kept through a change of owners
		final RouteLegs.Plan connecting = RouteLegs.plan(stops, Collections.emptyList(), null, cached(cache, snapshot, owned, found));
		assertTrue(connecting.isFound());
		assertFalse(cache.changeOwners(changed).isEmpty());
		assertTrue(cache.holds(connecting.consulted));
		assertFalse(cache.holds(kept.consulted));
		final RouteLegs.Plan shared = RouteLegs.plan(stops, Collections.emptyList(), "X", cached(cache, snapshot, merged, found));
		assertEquals(anywhere.cost, shared.cost, 1E-9);
	}

	@Test
	void legsKeptAcrossChangesAreWhatFindingThemAgainGives() throws Exception {
		for (long seed = 0; seed < 8; seed++) {
			final Random random = new Random(seed);
			final LegalNetworks network = LegalNetworks.generate(seed);
			final Map<UUID, TrackSections.Rail> all = TrackSectionsTest.railsOf(network);
			final List<UUID> keys = new ArrayList<>(new TreeSet<>(all.keySet()));
			final Map<UUID, TrackSections.Rail> current = new HashMap<>(all);
			final TrackSections sections = new TrackSections();
			sections.sync(current, Collections.emptyMap());
			final LegCache cache = new LegCache();
			for (int step = 0; step < 15; step++) {
				// A few rails taken away or laid again, but never platforms
				for (int i = 0; i < 2; i++) {
					final UUID key = keys.get(random.nextInt(keys.size()));
					if (all.get(key).savedRailId != 0) {
						continue;
					}
					if (current.remove(key) == null) {
						current.put(key, all.get(key));
					}
				}
				final TrackSections.Change change = sections.sync(current, Collections.emptyMap());
				final Set<Long> changed = new HashSet<>(change.added);
				changed.addAll(change.removed);
				final Set<Long> joined = new HashSet<>();
				change.added.forEach(hash -> {
					joined.add(sections.getSection(hash).getStart());
					joined.add(sections.getSection(hash).getEnd());
				});
				cache.change(changed, joined);
				final TrackSections.Snapshot snapshot = sections.snapshot();
				final int[] found = {0};
				final RouteLegs.LegSource keptSource = cached(cache, snapshot, Collections.emptyMap(), found);
				final RouteLegs.LegSource freshSource = fresh(snapshot, 0, Collections.emptyMap());
				for (final LegalNetworks.GeneratedRoute route : network.routes) {
					final Map.Entry<List<Long>, List<long[]>> stops = stopsOf(route, snapshot);
					final RouteLegs.Plan kept = RouteLegs.plan(stops.getKey(), stops.getValue(), null, keptSource);
					final RouteLegs.Plan again = RouteLegs.plan(stops.getKey(), stops.getValue(), null, freshSource);
					assertEquals(describe(again), describe(kept), "seed " + seed + " step " + step + " " + route);
				}
			}
		}
	}

	@Test
	void oneThreadOrEightFindTheSameLegs() throws Exception {
		final LegalNetworks network = LegalNetworks.generate(11);
		final TrackSections sections = new TrackSections();
		sections.sync(TrackSectionsTest.railsOf(network), Collections.emptyMap());
		final TrackSections.Snapshot snapshot = sections.snapshot();
		final List<String> one = new ArrayList<>();
		final RouteLegs.LegSource source = fresh(snapshot, 0, Collections.emptyMap());
		network.routes.forEach(route -> {
			final Map.Entry<List<Long>, List<long[]>> stops = stopsOf(route, snapshot);
			one.add(describe(RouteLegs.plan(stops.getKey(), stops.getValue(), null, source)));
		});
		final ExecutorService executor = Executors.newFixedThreadPool(8);
		try {
			final LegCache cache = new LegCache();
			final List<Future<String>> futures = new ArrayList<>();
			for (final LegalNetworks.GeneratedRoute route : network.routes) {
				futures.add(executor.submit(() -> {
					final Map.Entry<List<Long>, List<long[]>> stops = stopsOf(route, snapshot);
					return describe(RouteLegs.plan(stops.getKey(), stops.getValue(), null, cached(cache, snapshot, Collections.emptyMap(), new int[1])));
				}));
			}
			final List<String> eight = new ArrayList<>();
			for (final Future<String> future : futures) {
				eight.add(future.get());
			}
			assertEquals(one, eight);
		} finally {
			executor.shutdown();
		}
	}

	private static String describe(RouteLegs.Plan plan) {
		final StringBuilder builder = new StringBuilder().append(plan.reachedStops).append(':').append(plan.cost).append(':');
		plan.steps.forEach(step -> builder.append(Arrays.toString(step)));
		return builder.append(Arrays.toString(plan.stopSteps)).toString();
	}
}
