package mtr.path;

import mtr.data.Rail;
import mtr.data.RailAngle;
import mtr.data.RailType;
import mtr.data.TransportMode;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import transitsim.plan.JoinedPath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The old search, which stays in MTR when the rail graph moved into the simulation component.
 */
class PathFinderTest {

	private static PathData rail(int startZ, int endZ) {
		final BlockPos start = new BlockPos(0, 64, startZ);
		final BlockPos end = new BlockPos(0, 64, endZ);
		final Rail rail = new Rail(start, RailAngle.fromAngle(90), end, RailAngle.fromAngle(270), RailType.IRON, TransportMode.TRAIN);
		return new PathData(rail, 0, 0, start, end, 0);
	}

	@Test
	void oldSearchFindsTheSectionItCannotFollow() {
		assertEquals(1, PathFinder.findSectionWithWaypoints(Arrays.asList(Collections.emptyList(), Collections.singletonList("node"), Collections.emptyList()), 3));
		assertEquals(-1, PathFinder.findSectionWithWaypoints(Arrays.asList(Collections.emptyList(), Collections.emptyList()), 2));
		// The last stop has no next section, so anything left on it is ignored
		assertEquals(-1, PathFinder.findSectionWithWaypoints(Arrays.asList(Collections.emptyList(), Collections.singletonList("node")), 2));
		assertEquals(-1, PathFinder.findSectionWithWaypoints(null, 2));
	}

	/**
	 * Appending drops the shared first rail and gives the same rails to a plain list and to a joined path, which keeps
	 * the appended path as one piece rather than one piece per rail.
	 */
	@Test
	void appendPathAddsTheWholePartialPathAtOnce() {
		final PathData a = rail(0, 10), b = rail(10, 20), c = rail(20, 30), d = rail(30, 40);
		final List<PathData> plain = new ArrayList<>(Arrays.asList(a, b));
		final JoinedPath<PathData> joined = new JoinedPath<>(Arrays.asList(a, b));
		final List<PathData> partial = Arrays.asList(rail(10, 20), c, d);

		PathFinder.appendPath(plain, partial);
		PathFinder.appendPath(joined, partial);
		assertEquals(Arrays.asList(a, b, c, d), plain);
		assertEquals(plain, joined);
		assertSame(b, joined.get(1));
		assertEquals(2, joined.pieceCount());

		// Not starting on the last rail: everything is appended
		PathFinder.appendPath(plain, Collections.singletonList(a));
		assertEquals(5, plain.size());
		// Only the shared rail: nothing to add
		PathFinder.appendPath(joined, Collections.singletonList(rail(30, 40)));
		assertEquals(4, joined.size());
		assertEquals(2, joined.pieceCount());
		// No way found: the path is cleared, as before
		PathFinder.appendPath(plain, Collections.emptyList());
		assertTrue(plain.isEmpty());
	}
}
