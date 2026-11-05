package transitsim.sim;

import org.junit.jupiter.api.Test;
import transitsim.network.ThroatRoutes;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trains lock their way through station throats (L2): one way through a turnout at a time, the same way shared, the
 * train stopping before a throat it hasn't been given, and turnouts let go behind its tail.
 */
class ThroatLocksTest {

	/**
	 * Nodes packed as the game packs block positions, x and z only, along a line east at z 0, with a turnout at x 20.
	 */
	private static long node(int x, int z) {
		return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12;
	}

	@Test
	void positionsUnpackAsTheGamePacksThem() {
		final double[] position = ThroatLocks.position(node(-1234, 567));
		assertEquals(-1234, position[0]);
		assertEquals(567, position[1]);
	}

	@Test
	void crossingWaysThroughATurnoutGoOneAfterTheOther() {
		final RunReservations reservations = new RunReservations();
		final RunReservations.Move straight = RunReservations.Move.turnout(11, 10, 12);
		final RunReservations.Move crossing = RunReservations.Move.turnout(11, 10, 21);
		reservations.request(1, Collections.singletonList(straight), 0);
		reservations.request(2, Collections.singletonList(crossing), 1000);
		reservations.resolve(0);
		assertTrue(reservations.takeGrant(1));
		assertFalse(reservations.takeGrant(2));

		// Train 1 still holds it, so train 2 waits; once train 1's tail is past, train 2 gets it
		reservations.hold(1, Collections.singletonList(straight));
		reservations.request(2, Collections.singletonList(crossing), 1000);
		reservations.resolve(1000);
		assertFalse(reservations.takeGrant(2));
		reservations.hold(1, Collections.emptyList());
		reservations.request(2, Collections.singletonList(crossing), 1000);
		reservations.resolve(2000);
		assertTrue(reservations.takeGrant(2));
		assertEquals(Collections.singletonList(crossing), reservations.getHeld(2));
	}

	@Test
	void trainsTheSameWayShareATurnout() {
		final RunReservations reservations = new RunReservations();
		final RunReservations.Move straight = RunReservations.Move.turnout(11, 12, 10);
		reservations.hold(1, Collections.singletonList(RunReservations.Move.turnout(11, 10, 12)));
		reservations.request(2, Collections.singletonList(straight), 0);
		reservations.resolve(0);
		assertTrue(reservations.takeGrant(2));
	}

	/**
	 * A path along z 0: node 0 at x 0, then every 20 blocks; node 3, at x 60, is a turnout; the stop is rail 5, x 100 to
	 * 120, and the train starts in a siding on rail 0.
	 */
	private static ThroatLocks locks() {
		final long[] nodes = new long[8];
		for (int i = 0; i < nodes.length; i++) {
			nodes[i] = node(i * 20, 0);
		}
		final Map<Long, Integer> rails = new HashMap<>();
		rails.put(nodes[3], 3);
		return new ThroatLocks("path", nodes, new int[]{0, 5}, node -> rails.getOrDefault(node, 2));
	}

	@Test
	void theTrainStopsBeforeAThroatItHasntBeenGiven() {
		final ThroatLocks locks = locks();
		final List<ThroatRoutes.Item> entry = locks.getNextEntry(0);
		assertEquals(1, entry.size());
		assertEquals(3, entry.get(0).index);
		// The rail onto the turnout is rail 2, from node 2 to node 3
		assertTrue(locks.isBlocked(2, 0));
		assertFalse(locks.isBlocked(1, 0));
		assertFalse(locks.isBlocked(3, 0));
	}

	@Test
	void theTrainAsksOnlyOnceCloseEnough() {
		final ThroatLocks locks = locks();
		final RunReservations reservations = new RunReservations();
		// 200 blocks from where it would stop, braking in 50: not yet
		assertFalse(locks.approach(reservations, 1, Collections.emptyList(), 0, 0, index -> 200, 50, 0));
		// 70 blocks away: asks, is given it, and is let on
		assertTrue(locks.approach(reservations, 1, Collections.emptyList(), 0, 0, index -> 70, 50, 0));
		reservations.resolve(0);
		assertFalse(locks.approach(reservations, 1, Collections.emptyList(), 1, 0, index -> 50, 50, 0));
		assertFalse(locks.isBlocked(2, 1));
		assertEquals(1, locks.getHeld(2).size());
		// Let go once the tail is past it
		assertTrue(locks.getHeld(3).isEmpty());
	}

	@Test
	void aTrainStoppedInFrontOfTheThroatStillAsks() {
		// Stopped at the start of the rail onto the turnout, which is a long way from the turnout itself
		final ThroatLocks locks = locks();
		final RunReservations reservations = new RunReservations();
		assertTrue(locks.approach(reservations, 1, Collections.emptyList(), 1, 0, index -> index == 2 ? 0 : 400, 0, 0));
	}

	@Test
	void aZoneIsAskedForWithTheThroatAndLetGoOnceTheTailHasLeftIt() {
		// As locks(), with rails 3 and 4 (x 60 to 100, just before the stop) painted as zone 9
		final long[] nodes = new long[8];
		for (int i = 0; i < nodes.length; i++) {
			nodes[i] = node(i * 20, 0);
		}
		final Map<Long, Integer> rails = new HashMap<>();
		rails.put(nodes[3], 3);
		final Map<java.util.UUID, Long> zones = new HashMap<>();
		zones.put(transitsim.network.RailKeys.of(nodes[3], nodes[4]), 9L);
		zones.put(transitsim.network.RailKeys.of(nodes[4], nodes[5]), 9L);
		final ThroatLocks locks = new ThroatLocks("path", nodes, new int[]{0, 5}, node -> rails.getOrDefault(node, 2), rail -> zones.getOrDefault(rail, 0L));
		final List<ThroatRoutes.Item> entry = locks.getNextEntry(0);
		assertEquals(2, entry.size());
		assertTrue(entry.stream().anyMatch(ThroatRoutes.Item::isZone));
		assertTrue(locks.isBlocked(2, 0));

		final RunReservations reservations = new RunReservations();
		assertTrue(locks.approach(reservations, 1, Collections.emptyList(), 0, 0, index -> 10, 50, 0));
		reservations.resolve(0);
		assertFalse(locks.approach(reservations, 1, Collections.emptyList(), 1, 0, index -> 10, 50, 0));
		// The turnout goes once the tail is past node 3; the zone only once it has left rail 4
		assertEquals(2, locks.getHeld(2).size());
		assertEquals(1, locks.getHeld(4).size());
		assertTrue(locks.getHeld(4).get(0).zone);
		assertTrue(locks.getHeld(5).isEmpty());
	}

	@Test
	void aZoneIsOneTrainsAtATimeEvenTheSameWay() {
		final RunReservations reservations = new RunReservations();
		final RunReservations.Move zone = RunReservations.Move.zone(9);
		reservations.hold(1, Collections.singletonList(zone));
		reservations.request(2, Collections.singletonList(zone), 0);
		reservations.resolve(0);
		assertFalse(reservations.takeGrant(2));
		assertEquals(Collections.singletonList(zone), reservations.getHeld(1));
		reservations.hold(1, Collections.emptyList());
		reservations.request(2, Collections.singletonList(zone), 0);
		reservations.resolve(1000);
		assertTrue(reservations.takeGrant(2));
	}

	@Test
	void theExitRouteGoesWithTheRunOutOfTheSiding() {
		// From the siding, the turnout at node 3 is on its way out as well
		final ThroatLocks locks = locks();
		assertEquals(1, locks.getExit(0).size());
		assertTrue(locks.getExit(5).isEmpty());
	}
}
