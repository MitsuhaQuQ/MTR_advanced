package transitsim.motion;

import org.junit.jupiter.api.Test;
import transitsim.motion.PathRelocator.Position;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PathRelocatorTest {

	private static List<Double> distances(double... lengths) {
		final List<Double> distances = new ArrayList<>();
		double sum = 0;
		for (final double length : lengths) {
			sum += length;
			distances.add(sum);
		}
		return distances;
	}

	@Test
	void trainKeepsItsRailWhenEarlierRailsChange() {
		final Position position = PathRelocator.locate(Arrays.asList("a", "b", "c"), distances(10, 10, 10), Arrays.asList("x", "y", "a", "b", "c"), distances(5, 5, 10, 10, 10), 15);
		assertEquals(3, position.railIndex);
		assertEquals(25, position.railProgress, 1E-9);
	}

	@Test
	void changedRailLengthKeepsTheProportion() {
		final Position position = PathRelocator.locate(Arrays.asList("a", "b"), distances(10, 10), Arrays.asList("a", "b"), distances(10, 40), 15);
		assertEquals(30, position.railProgress, 1E-9);
	}

	@Test
	void stoppedTrainStaysAtTheEndOfItsRail() {
		final Position position = PathRelocator.locate(Arrays.asList("a", "platform", "c"), distances(10, 10, 10), Arrays.asList("z", "a", "platform", "c"), distances(7, 10, 10, 10), 20);
		assertEquals(2, position.railIndex);
		assertEquals(27, position.railProgress, 1E-9);
	}

	@Test
	void repeatedRailMatchesTheSamePass() {
		final List<String> oldRails = Arrays.asList("loop", "b", "loop", "c");
		final Position position = PathRelocator.locate(oldRails, distances(10, 10, 10, 10), oldRails, distances(10, 10, 10, 10), 25);
		assertEquals(2, position.railIndex);
		assertEquals(25, position.railProgress, 1E-9);
	}

	@Test
	void fewerPassesFallBackToTheLastOne() {
		final Position position = PathRelocator.locate(Arrays.asList("loop", "b", "loop"), distances(10, 10, 10), Arrays.asList("loop", "c"), distances(10, 10), 25);
		assertEquals(0, position.railIndex);
		assertEquals(5, position.railProgress, 1E-9);
	}

	@Test
	void trainOnARemovedRailMovesToTheNextSharedRail() {
		final Position position = PathRelocator.locate(Arrays.asList("a", "old", "c"), distances(10, 10, 10), Arrays.asList("a", "new", "c"), distances(10, 30, 10), 15);
		assertEquals(2, position.railIndex);
		assertEquals(40, position.railProgress, 1E-9);
	}

	@Test
	void noSharedRailAheadSendsTheTrainBack() {
		assertNull(PathRelocator.locate(Arrays.asList("a", "b"), distances(10, 10), Arrays.asList("a", "c"), distances(10, 10), 15));
	}
}
