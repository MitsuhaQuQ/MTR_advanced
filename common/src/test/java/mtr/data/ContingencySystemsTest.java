package mtr.data;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.value.Value;
import transitsim.network.PlatformGraph;
import transitsim.plan.Contingencies;
import transitsim.plan.Contingency;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The contingency plans of each rail system (D2): a route falls under the system of its network, whichever depot runs it;
 * systems are kept in saves and sent to the players.
 */
class ContingencySystemsTest {

	/**
	 * Platforms 1 to 3 in network 10, 4 and 5 in network 20.
	 */
	private static PlatformGraph graph() {
		final long[] platformIds = {1, 2, 3, 4, 5};
		final long[] networks = {10, 10, 10, 20, 20};
		final boolean[] usable = new boolean[10];
		final int[][] targets = new int[10][0];
		final double[][] costs = new double[10][0];
		return new PlatformGraph(platformIds, networks, usable, targets, costs);
	}

	private static Route route(long id, long... platformIds) {
		final Route route = new Route(id, TransportMode.TRAIN);
		for (final long platformId : platformIds) {
			route.platformIds.add(new Route.RoutePlatform(platformId));
		}
		return route;
	}

	private static Contingencies withPlan(long planId) {
		final Contingencies contingencies = new Contingencies();
		final Contingency plan = new Contingency();
		plan.id = planId;
		plan.name = "plan " + planId;
		contingencies.plans.put(planId, plan);
		return contingencies;
	}

	@Test
	void aRouteFallsUnderTheSystemOfItsNetwork() {
		final ContingencySystems systems = new ContingencySystems();
		final PlatformGraph graph = graph();
		// A network with no system yet takes its lowest platform as its anchor
		assertEquals(1, systems.anchorOf(10, graph));
		assertEquals(4, systems.anchorOf(20, graph));
		systems.systems.put(2L, withPlan(7));
		// Once it has one, that one, though not the lowest
		assertEquals(2, systems.anchorOf(10, graph));
		assertEquals(Collections.singletonList(systems.systems.get(2L)), systems.forRoute(route(100, 3, 1), graph));
		assertTrue(systems.forRoute(route(101, 5, 4), graph).isEmpty());
		assertTrue(systems.forRoute(route(102), graph).isEmpty());
	}

	@Test
	void systemsJoinedByRailsAreBothKeptTheLowerFirst() {
		final ContingencySystems systems = new ContingencySystems();
		systems.systems.put(3L, withPlan(1));
		systems.systems.put(1L, withPlan(1));
		// Two systems of what were two networks, now one: both of them, the lower anchor first
		assertEquals(Arrays.asList(1L, 3L), systems.anchorsForRoute(route(100, 2), graph()));
		assertEquals(2, systems.forRoute(route(100, 2), graph()).size());
		assertEquals(1, systems.anchorOf(10, graph()));
	}

	@Test
	void aSystemWhoseAnchorIsRemovedMovesToAnotherPlatformOfItsNetwork() {
		final ContingencySystems systems = new ContingencySystems();
		systems.systems.put(2L, withPlan(7));
		systems.systems.put(4L, withPlan(8));
		final Set<Long> platforms = new HashSet<>(Arrays.asList(1L, 2L, 3L, 4L, 5L));
		assertTrue(systems.keepAnchors(graph(), platforms::contains).isEmpty());
		assertEquals(Arrays.asList(1L, 3L), systems.backups.get(2L));
		// Platform 2 removed: to platform 1, the first noted
		platforms.remove(2L);
		assertEquals(Collections.singletonMap(2L, 1L), systems.keepAnchors(graph(), platforms::contains));
		assertEquals(7, systems.systems.get(1L).plans.keySet().iterator().next());
		assertFalse(systems.systems.containsKey(2L));
		// With none of its platforms left it stays as it is, nothing lost
		platforms.remove(4L);
		platforms.remove(5L);
		assertTrue(systems.keepAnchors(graph(), platforms::contains).isEmpty());
		assertTrue(systems.systems.containsKey(4L));
	}

	@Test
	void systemsAreSavedAndSent() throws IOException {
		final ContingencySystems systems = new ContingencySystems();
		final Contingencies contingencies = withPlan(3);
		contingencies.active.add(3L);
		contingencies.adhoc.put(55L, new Contingency());
		systems.systems.put(4L, contingencies);

		final ContingencySystems.Entry entry = new ContingencySystems.Entry(4, contingencies, Arrays.asList(5L, 6L));
		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(entry.messagePackLength());
		entry.toMessagePack(packer);
		packer.close();
		final Map<String, Value> map = RailwayData.castMessagePackValueToSKMap(MessagePack.newDefaultUnpacker(packer.toByteArray()).unpackValue());
		final ContingencySystems.Entry read = new ContingencySystems.Entry(map);
		assertEquals(4, read.anchor);
		assertEquals(Arrays.asList(5L, 6L), read.backups);
		assertEquals(contingencies.plans, read.toContingencies().plans);
		assertEquals(contingencies.active, read.toContingencies().active);
		assertEquals(contingencies.adhoc, read.toContingencies().adhoc);

		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		systems.write(packet);
		final ContingencySystems received = new ContingencySystems();
		received.read(packet);
		assertEquals(contingencies.plans, received.systems.get(4L).plans);
		assertEquals(contingencies.adhoc, received.systems.get(4L).adhoc);
	}
}
