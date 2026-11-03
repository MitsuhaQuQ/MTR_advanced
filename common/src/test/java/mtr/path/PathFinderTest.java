package mtr.path;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The old search, which stays in MTR when the rail graph moved into the simulation component.
 */
class PathFinderTest {

	@Test
	void oldSearchFindsTheSectionItCannotFollow() {
		assertEquals(1, PathFinder.findSectionWithWaypoints(Arrays.asList(Collections.emptyList(), Collections.singletonList("node"), Collections.emptyList()), 3));
		assertEquals(-1, PathFinder.findSectionWithWaypoints(Arrays.asList(Collections.emptyList(), Collections.emptyList()), 2));
		// The last stop has no next section, so anything left on it is ignored
		assertEquals(-1, PathFinder.findSectionWithWaypoints(Arrays.asList(Collections.emptyList(), Collections.singletonList("node")), 2));
		assertEquals(-1, PathFinder.findSectionWithWaypoints(null, 2));
	}
}
