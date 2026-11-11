package mtr.audio;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SpeakerListenersTest {
	private final UUID player = new UUID(0, 1);
	private final Set<UUID> present = Collections.singleton(player);
	private final Set<UUID> empty = Collections.emptySet();

	@Test
	void returningAfterManyLeasePeriodsKeepsTheSamePlayback() {
		final SpeakerListeners listeners = new SpeakerListeners();
		assertEquals(present, listeners.update(present, present, false));
		for (int second = 0; second < 120; second++) {
			assertTrue(listeners.update(present, empty, false).isEmpty());
			assertEquals(present, listeners.update(present, empty, true));
		}
		assertTrue(listeners.update(present, present, false).isEmpty());
		assertEquals(present, listeners.update(present, present, true));
	}

	@Test
	void stoppingStillReachesListenersWhoHaveLeftTheArea() {
		final SpeakerListeners listeners = new SpeakerListeners();
		listeners.update(present, present, false);
		listeners.update(present, empty, true);
		assertEquals(present, listeners.clear());
		assertTrue(listeners.update(present, empty, true).isEmpty());
		assertEquals(present, listeners.update(present, present, false));
	}

	@Test
	void disconnectAndDimensionChangesReleaseTheSubscription() {
		final SpeakerListeners listeners = new SpeakerListeners();
		listeners.update(present, present, false);
		assertTrue(listeners.update(empty, empty, true).isEmpty());
		assertTrue(listeners.update(present, empty, true).isEmpty());
		assertTrue(listeners.clear().isEmpty());
	}
}
