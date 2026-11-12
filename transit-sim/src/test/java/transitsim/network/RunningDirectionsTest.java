package transitsim.network;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The way each track of a line runs by its running side (user, 2026-11-12). x east, z south, so looking east the right
 * is the south (larger z). Nodes are numbered growing with x, so 1 is eastwards.
 */
class RunningDirectionsTest {

	private static final Set<String> LINE = Collections.singleton("L");

	private static long node(int x, int z) {
		return (x + 100000L) * 1000000L + z + 100000L;
	}

	private static RunningDirections.Rail rail(String name, int x1, int z1, int x2, int z2, int side) {
		return rail(name, x1, z1, x2, z2, side, LINE);
	}

	private static RunningDirections.Rail rail(String name, int x1, int z1, int x2, int z2, int side, Set<String> lines) {
		return new RunningDirections.Rail(UUID.nameUUIDFromBytes(name.getBytes()), node(x1, z1), node(x2, z2), RunningDirections.straight(x1, z1, x2, z2), lines, side);
	}

	private static Integer way(Map<UUID, Integer> directions, String name) {
		return directions.get(UUID.nameUUIDFromBytes(name.getBytes()));
	}

	@Test
	void ofTwoTracksTheOneOnTheRunningSideRunsThatWay() {
		final Map<UUID, Integer> right = RunningDirections.compute(Arrays.asList(rail("north", 0, 0, 300, 0, RunningDirections.RIGHT), rail("south", 0, 8, 300, 8, RunningDirections.RIGHT)));
		assertEquals(1, way(right, "south"), "right hand: eastbound on the south track");
		assertEquals(-1, way(right, "north"));
		final Map<UUID, Integer> left = RunningDirections.compute(Arrays.asList(rail("north", 0, 0, 300, 0, RunningDirections.LEFT), rail("south", 0, 8, 300, 8, RunningDirections.LEFT)));
		assertEquals(1, way(left, "north"), "left hand: eastbound on the north track");
		assertEquals(-1, way(left, "south"));
	}

	@Test
	void theWayDoesNotDependOnWhichWayTheRailWasLaid() {
		final Map<UUID, Integer> directions = RunningDirections.compute(Arrays.asList(rail("north", 300, 0, 0, 0, RunningDirections.RIGHT), rail("south", 300, 8, 0, 8, RunningDirections.RIGHT)));
		assertEquals(1, way(directions, "south"));
		assertEquals(-1, way(directions, "north"));
	}

	@Test
	void ofFourBothOnTheRunningSideOfTheMiddle() {
		final List<RunningDirections.Rail> rails = new ArrayList<>();
		for (int z : new int[]{0, 5, 10, 15}) {
			rails.add(rail("z" + z, 0, z, 300, z, RunningDirections.RIGHT));
		}
		final Map<UUID, Integer> directions = RunningDirections.compute(rails);
		assertEquals(-1, way(directions, "z0"));
		assertEquals(-1, way(directions, "z5"));
		assertEquals(1, way(directions, "z10"));
		assertEquals(1, way(directions, "z15"));
	}

	@Test
	void theMiddleOfThreeAndATrackAloneHaveNone() {
		final Map<UUID, Integer> three = RunningDirections.compute(Arrays.asList(
				rail("a", 0, 0, 300, 0, RunningDirections.RIGHT), rail("b", 0, 5, 300, 5, RunningDirections.RIGHT), rail("c", 0, 10, 300, 10, RunningDirections.RIGHT)));
		assertNull(way(three, "b"));
		assertEquals(-1, way(three, "a"));
		assertEquals(1, way(three, "c"));
		assertTrue(RunningDirections.compute(Collections.singletonList(rail("alone", 0, 0, 300, 0, RunningDirections.RIGHT))).isEmpty());
	}

	@Test
	void tracksOfOtherLinesOrNoneAreNotItsPair() {
		final Map<UUID, Integer> directions = RunningDirections.compute(Arrays.asList(
				rail("x", 0, 0, 300, 0, RunningDirections.RIGHT, Collections.singleton("X")),
				rail("y", 0, 8, 300, 8, RunningDirections.RIGHT, Collections.singleton("Y")),
				rail("depot", 0, 16, 300, 16, RunningDirections.RIGHT, Collections.emptySet())));
		assertTrue(directions.isEmpty(), directions.toString());
	}

	@Test
	void linesOfDifferentSidesAreNotPairedEither() {
		final Map<UUID, Integer> directions = RunningDirections.compute(Arrays.asList(rail("north", 0, 0, 300, 0, RunningDirections.RIGHT), rail("south", 0, 8, 300, 8, RunningDirections.LEFT)));
		assertTrue(directions.isEmpty(), directions.toString());
	}

	@Test
	void whereTheTracksSwapSidesEachKeepsItsWayAlongIt() {
		// Track 1 runs west of x 100 on the north, crosses over track 2 between 100 and 200 (no node where they cross) and runs
		// on the south from 200 to 500; track 2 the other way round. East of 200, longer than the west part, right hand running
		// has track 1 eastbound: it is eastbound all along, though on the north west of 100
		final Map<UUID, Integer> directions = RunningDirections.compute(Arrays.asList(
				rail("1 west", 0, 0, 100, 0, RunningDirections.RIGHT), rail("1 over", 100, 0, 200, 8, RunningDirections.RIGHT), rail("1 east", 200, 8, 500, 8, RunningDirections.RIGHT),
				rail("2 west", 0, 8, 100, 8, RunningDirections.RIGHT), rail("2 over", 100, 8, 200, 0, RunningDirections.RIGHT), rail("2 east", 200, 0, 500, 0, RunningDirections.RIGHT)));
		for (final String part : new String[]{"west", "over", "east"}) {
			assertEquals(1, way(directions, "1 " + part), "track 1 " + part);
			assertEquals(-1, way(directions, "2 " + part), "track 2 " + part);
		}
	}

	@Test
	void aTurnoutEndsARunOfRails() {
		// The south track splits at x 150 into a straight track and a siding beside it with no line: each part decides alone,
		// and the siding, with nothing beside it of its line, has no way
		final Map<UUID, Integer> directions = RunningDirections.compute(Arrays.asList(
				rail("north", 0, 0, 300, 0, RunningDirections.RIGHT),
				rail("south 1", 0, 8, 150, 8, RunningDirections.RIGHT), rail("south 2", 150, 8, 300, 8, RunningDirections.RIGHT),
				rail("siding", 150, 8, 300, 20, RunningDirections.RIGHT, Collections.emptySet())));
		assertEquals(1, way(directions, "south 1"));
		assertEquals(1, way(directions, "south 2"));
		assertNull(way(directions, "siding"));
	}
}
