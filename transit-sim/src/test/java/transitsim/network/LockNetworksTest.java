package transitsim.network;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The new logic and the old don't share track: a network of depots' paths with a depot not running by duties has throat
 * locks off for all of its depots.
 */
class LockNetworksTest {

	@Test
	void depotsSharingTrackWithAnOldOneAreOff() {
		final LockNetworks networks = new LockNetworks();
		networks.addDepot(1, true);
		networks.addPath(1, new long[]{1, 2, 3});
		networks.addDepot(2, false);
		networks.addPath(2, new long[]{10, 3, 11});
		// Apart from both
		networks.addDepot(3, true);
		networks.addPath(3, new long[]{20, 21});
		final LockNetworks.Result result = networks.result();
		assertEquals(Collections.singleton(1L), result.offDepots);
		assertEquals(2, result.networks);
		assertEquals(1, result.mixed.size());
		assertEquals(Collections.singleton(2L), result.mixed.get(0).oldDepots);
		assertEquals(Collections.singleton(1L), result.mixed.get(0).newDepots);
	}

	@Test
	void anyOfADepotsPathsJoinsIt() {
		final LockNetworks networks = new LockNetworks();
		networks.addDepot(1, true);
		networks.addPath(1, new long[]{1, 2});
		// A duty's path of depot 1 runs elsewhere, onto the old depot's track
		networks.addPath(1, new long[]{5, 6});
		networks.addDepot(2, false);
		networks.addPath(2, new long[]{6, 7});
		assertEquals(Collections.singleton(1L), networks.result().offDepots);
	}

	@Test
	void oldDepotsOnTheirOwnTurnNothingOff() {
		final LockNetworks networks = new LockNetworks();
		networks.addDepot(1, false);
		networks.addPath(1, new long[]{1, 2});
		networks.addDepot(2, false);
		networks.addPath(2, new long[]{2, 3});
		assertTrue(networks.result().mixed.isEmpty());
		assertTrue(networks.result().offDepots.isEmpty());
	}

	@Test
	void withNoOldDepotEverythingIsOn() {
		final LockNetworks networks = new LockNetworks();
		networks.addDepot(1, true);
		networks.addPath(1, new long[]{1, 2, 3});
		networks.addDepot(2, true);
		networks.addPath(2, new long[]{3, 4});
		final LockNetworks.Result result = networks.result();
		assertTrue(result.offDepots.isEmpty());
		assertTrue(result.mixed.isEmpty());
		assertEquals(1, result.networks);
	}
}
