package transitsim.fare;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The faster fare search (2026-11-12: one search from each group entered in for every group left by, stopped early,
 * paths kept, the longest distance only when needed) gives exactly what the one before did ({@link ReferenceFareNetwork}),
 * on random networks with lines of their own, companies of several lines and track shared by groups.
 */
class FarePathEquivalenceTest {

	private static final String[] GROUPS = {"A", "B", "C", "D", "E"};

	@Test
	void pathsAreTheSameAsBefore() {
		for (int seed = 0; seed < 40; seed++) {
			final Random random = new Random(seed);
			final List<FareNetwork.Leg> legs = randomLegs(random);
			final FareNetwork network = new FareNetwork(legs);
			final ReferenceFareNetwork reference = new ReferenceFareNetwork(legs);
			for (int i = 0; i < 150; i++) {
				final long from = random.nextInt(60);
				final long to = random.nextInt(60);
				final Set<String> start = randomGroups(random);
				final Set<String> end = randomGroups(random);
				final String where = "seed " + seed + " " + from + start + " → " + to + end;
				assertSame(reference.find(from, to, start, end), network.find(from, to, start, end), where);
				// Again, from what was kept
				assertSame(reference.find(from, to, start, end), network.find(from, to, start, end), where + " kept");
				// With several sets of end groups at once, each as on its own
				final List<Set<String>> sets = Arrays.asList(end, randomGroups(random), Collections.emptySet(), randomGroups(random));
				final FareNetwork.Path[] paths = new FareNetwork(legs).find(from, to, start, sets);
				for (int j = 0; j < sets.size(); j++) {
					assertSame(reference.find(from, to, start, sets.get(j)), paths[j], where + " set " + j);
				}
			}
			for (final String group : GROUPS) {
				assertEquals(reference.getLongestBlocks(group), network.getLongestBlocks(group), "seed " + seed + " longest " + group);
			}
		}
	}

	@Test
	void faresAreTheSameAsBefore() {
		for (int seed = 0; seed < 40; seed++) {
			final Random random = new Random(1000 + seed);
			final List<FareNetwork.Leg> legs = randomLegs(random);
			final FareNetwork network = new FareNetwork(legs);
			final ReferenceFareNetwork reference = new ReferenceFareNetwork(legs);
			final Map<String, FareLevels> levels = randomLevels(random);
			for (int i = 0; i < 150; i++) {
				final FareTrip.Gate entry = randomGate(random);
				final FareTrip.Gate exit = randomGate(random);
				final FareTrip.Result result = FareTrip.charge(network, levels, entry, exit, random.nextInt(200));
				final int expected = referenceFare(reference, levels::get, entry, exit);
				if (entry.station != exit.station) {
					assertEquals(expected, result.fare, "seed " + seed + " " + entry.station + entry.groups + entry.shared + " → " + exit.station + exit.groups + exit.shared);
				}
			}
		}
	}

	/**
	 * Large enough to matter, small enough to be joined in many ways: 60 stations, lines of 5 to 14 stops in 5 groups,
	 * some legs run by two groups over the same track.
	 */
	private static List<FareNetwork.Leg> randomLegs(Random random) {
		final List<FareNetwork.Leg> legs = new ArrayList<>();
		final int lines = 6 + random.nextInt(8);
		for (int line = 0; line < lines; line++) {
			final String group = GROUPS[random.nextInt(GROUPS.length)];
			long previous = random.nextInt(60);
			final int stops = 5 + random.nextInt(10);
			for (int stop = 1; stop < stops; stop++) {
				final long next = random.nextInt(60);
				final long blocks = 500 + random.nextInt(4000);
				legs.add(new FareNetwork.Leg(group, previous, next, blocks));
				if (random.nextInt(6) == 0) {
					legs.add(new FareNetwork.Leg(GROUPS[random.nextInt(GROUPS.length)], previous, next, blocks + random.nextInt(3) - 1));
				}
				previous = next;
			}
		}
		return legs;
	}

	private static Set<String> randomGroups(Random random) {
		final Set<String> groups = new LinkedHashSet<>();
		final int count = random.nextInt(3);
		for (int i = 0; i < count; i++) {
			groups.add(GROUPS[random.nextInt(GROUPS.length)]);
		}
		return groups;
	}

	private static FareTrip.Gate randomGate(Random random) {
		final Set<String> groups = randomGroups(random);
		return new FareTrip.Gate(random.nextInt(60), groups, groups.size() > 1 || random.nextBoolean() && !groups.isEmpty());
	}

	private static Map<String, FareLevels> randomLevels(Random random) {
		final Map<String, FareLevels> levels = new HashMap<>();
		for (final String group : GROUPS) {
			final FareLevels groupLevels = new FareLevels();
			groupLevels.startFare = 1 + random.nextInt(4);
			groupLevels.initialBlocks = random.nextInt(8000);
			groupLevels.unitBlocks = 1000 + random.nextInt(4000);
			groupLevels.unitFare = random.nextInt(3);
			switch (random.nextInt(4)) {
				case 0:
					groupLevels.maxBlocks = 5000 + random.nextInt(30000);
					break;
				case 1:
					groupLevels.setTiers(new long[]{3000, 9000}, new int[]{2, 5, 7});
					break;
				case 2:
					groupLevels.cap = 4;
					break;
				default:
					break;
			}
			if (random.nextInt(5) > 0) {
				levels.put(group, groupLevels);
			}
		}
		return levels;
	}

	/**
	 * {@link FareTrip#charge} as it was before, on the network as it was: a search for each pair of groups, the longest
	 * distance always worked out.
	 */
	private static int referenceFare(ReferenceFareNetwork network, Function<String, FareLevels> levels, FareTrip.Gate entry, FareTrip.Gate exit) {
		final Function<String, FareLevels> levelsOf = group -> {
			final FareLevels found = group == null ? null : levels.apply(group);
			return found == null ? new FareLevels() : found;
		};
		Integer bestFare = null;
		int bestCrossings = Integer.MAX_VALUE;
		long bestBlocks = Long.MAX_VALUE;
		final Set<String> entryGroups = entry.groups.isEmpty() ? Collections.singleton(null) : entry.groups;
		final Set<String> exitGroups = exit.groups.isEmpty() ? Collections.singleton(null) : exit.groups;
		for (final String entryGroup : entryGroups) {
			for (final String exitGroup : exitGroups) {
				final ReferenceFareNetwork.Path path = network.find(entry.station, exit.station, entryGroup == null ? Collections.emptySet() : Collections.singleton(entryGroup), exitGroup == null ? Collections.emptySet() : Collections.singleton(exitGroup));
				if (path == null) {
					continue;
				}
				int fare = 0;
				for (final ReferenceFareNetwork.Part part : path.parts) {
					fare += levelsOf.apply(part.group).getFare(part.blocks, network.getLongestBlocks(part.group));
				}
				final long blocks = path.getBlocks();
				final boolean better;
				if (bestFare == null) {
					better = true;
				} else if (entry.shared && exit.shared) {
					better = fare < bestFare || fare == bestFare && (path.crossings < bestCrossings || path.crossings == bestCrossings && blocks < bestBlocks);
				} else {
					better = path.crossings < bestCrossings || path.crossings == bestCrossings && (blocks < bestBlocks || blocks == bestBlocks && fare < bestFare);
				}
				if (better) {
					bestFare = fare;
					bestCrossings = path.crossings;
					bestBlocks = blocks;
				}
			}
		}
		return bestFare == null ? -1 : bestFare;
	}

	private static void assertSame(ReferenceFareNetwork.Path expected, FareNetwork.Path actual, String where) {
		if (expected == null) {
			assertNull(actual, where);
			return;
		}
		assertNotNull(actual, where);
		assertEquals(expected.crossings, actual.crossings, where);
		assertEquals(expected.parts.size(), actual.parts.size(), where);
		for (int i = 0; i < expected.parts.size(); i++) {
			assertEquals(expected.parts.get(i).group, actual.parts.get(i).group, where + " part " + i);
			assertEquals(expected.parts.get(i).blocks, actual.parts.get(i).blocks, where + " part " + i);
		}
	}
}
