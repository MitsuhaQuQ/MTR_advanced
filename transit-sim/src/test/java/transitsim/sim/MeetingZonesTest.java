package transitsim.sim;

import org.junit.jupiter.api.Test;
import transitsim.plan.MeetingZones;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Passing points with both tracks in one interlocking zone (2026-11-14, user feedback): a single line with a dead-end
 * platform at each end and a passing loop between, the loop's turnouts painted in the interlocking colour, platforms and all.
 */
class MeetingZonesTest {

	// Nodes: v1's platform end 1, the south turnout 2, the loop's east track 3-4, the north turnout 5, the line 5-6, v2's
	// platform 6-7, the loop's west track 8-9
	private static List<TestTrack> roundTrip() {
		return Arrays.asList(
				rail(1, 2), rail(2, 3), rail(3, 4), rail(4, 5), rail(5, 6), rail(6, 7),
				rail(7, 6), rail(6, 5), rail(5, 8), rail(8, 9), rail(9, 2), rail(2, 1)
		);
	}

	private static TestTrack rail(long start, long end) {
		return new TestTrack(start, end, false, 0, 0, 0);
	}

	private static UUID key(long start, long end) {
		return rail(start, end).getTrackKey();
	}

	@Test
	void bothEndsOfALoopPaintedWithTheirTracksAreFound() {
		final Map<UUID, Long> zones = new HashMap<>();
		// The south turnout with v1's platform, the north one with the line and v2's platform, as the feedback had them
		for (final UUID rail : Arrays.asList(key(1, 2), key(2, 3), key(9, 2))) {
			zones.put(rail, 1L);
		}
		for (final UUID rail : Arrays.asList(key(4, 5), key(5, 6), key(6, 7), key(5, 8))) {
			zones.put(rail, 2L);
		}
		final List<MeetingZones.Finding<TestTrack>> found = MeetingZones.find(Collections.singletonList(roundTrip()), zones::get);
		assertEquals(2, found.size());
		assertEquals(2, found.get(0).zone);
		assertEquals(key(5, 6), found.get(0).shared.getTrackKey());
		assertEquals(key(4, 5), found.get(0).from.getTrackKey());
		assertEquals(key(5, 8), found.get(0).to.getTrackKey());
		assertEquals(1, found.get(1).zone);
		assertEquals(key(1, 2), found.get(1).shared.getTrackKey());
	}

	@Test
	void aZoneWithOneTrackOfTheLoopOrNoneIsFine() {
		// Only the line and platforms painted, or each track of the loop in a zone of its own
		final Map<UUID, Long> zones = new HashMap<>();
		zones.put(key(5, 6), 2L);
		zones.put(key(4, 5), 3L);
		zones.put(key(5, 8), 4L);
		assertTrue(MeetingZones.find(Collections.singletonList(roundTrip()), zones::get).isEmpty());
		assertTrue(MeetingZones.find(Collections.singletonList(roundTrip()), rail -> null).isEmpty());
	}

	@Test
	void trainsRunningStraightThroughAZoneAreNoPassingPoint() {
		// Double track through a painted throat, each track one way
		final List<List<TestTrack>> paths = new ArrayList<>();
		paths.add(Arrays.asList(rail(1, 2), rail(2, 3), rail(3, 4)));
		paths.add(Arrays.asList(rail(14, 13), rail(13, 12), rail(12, 11)));
		assertTrue(MeetingZones.find(paths, rail -> 1L).isEmpty());
	}
}
