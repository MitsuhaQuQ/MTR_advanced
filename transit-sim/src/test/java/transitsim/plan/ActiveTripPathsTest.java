package transitsim.plan;

import org.junit.jupiter.api.Test;
import transitsim.sim.SimDataset;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ActiveTripPathsTest {
	private TripPath<SimDataset.DataTrack> path(String key) {
		return new TripPath<>(key, -1, -1, new int[0], 0, 0, 0, -1, Collections.emptyList());
	}

	@Test
	void changedDutiesKeepOnlyValidActivePathsWithoutReplacingNewPaths() {
		TripPath<SimDataset.DataTrack> old = path("old duty"), changed = path("same key"), fresh = path("same key"), broken = path("removed rails");
		Map<String, TripPath<SimDataset.DataTrack>> replacement = new HashMap<>();
		replacement.put(fresh.key, fresh);
		ActiveTripPaths.retain(replacement, Arrays.asList(old, changed, broken, null), p -> p != broken);
		assertSame(old, replacement.get(old.key));
		assertSame(fresh, replacement.get(fresh.key));
		assertFalse(replacement.containsKey(broken.key));
		assertEquals(2, replacement.size());
	}
}
