package mtr.advanced;

import io.netty.buffer.Unpooled;
import mtr.data.Rail;
import mtr.data.RailAngle;
import mtr.data.RailType;
import mtr.data.RoutePaths;
import mtr.data.TransportMode;
import mtr.path.DeterministicPathFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import transitsim.network.RailGraph;
import transitsim.network.RailKeys;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The route track diagram carries each rail's nodes and isolation, so the editor can show and change it.
 */
class RouteLayoutsTest {

	@Test
	void eachRailCarriesItsNodesAndIsolation() {
		final Map<BlockPos, Map<BlockPos, Rail>> rails = new HashMap<>();
		final long a = pos(0), b = pos(100), c = pos(200);
		addRail(rails, 0, 100);
		addRail(rails, 100, 200);
		final RailGraph<Rail> graph = DeterministicPathFinder.compile(rails);
		final Map<UUID, Integer> statuses = new HashMap<>();
		statuses.put(RailKeys.of(a, b), RoutePaths.RAIL_BOUNDARY);
		statuses.put(RailKeys.of(b, c), RoutePaths.RAIL_TAKEN);

		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		RouteLayouts.write(7, graph, new long[]{a, b, c}, new ArrayList<>(), new ArrayList<>(), RouteLayouts.DEFAULT_HALF_WIDTH, statuses, Collections.singleton(b), packet);
		final RouteLayouts.Layout layout = RouteLayouts.read(packet);

		assertTrue(layout.found);
		assertEquals(layout.pieces.size(), layout.keys.size());
		final Map<UUID, Long> read = new HashMap<>();
		layout.keys.forEach(key -> read.put(RailKeys.of(key[1], key[2]), key[0]));
		assertEquals((long) RoutePaths.RAIL_BOUNDARY, read.get(RailKeys.of(a, b)));
		assertEquals((long) RoutePaths.RAIL_TAKEN, read.get(RailKeys.of(b, c)));
		assertEquals(1, layout.boundaryNodes.size());
		assertEquals(0, packet.readableBytes());
	}

	private static long pos(int z) {
		return new BlockPos(0, 64, z).asLong();
	}

	private static void addRail(Map<BlockPos, Map<BlockPos, Rail>> rails, int startZ, int endZ) {
		final BlockPos start = new BlockPos(0, 64, startZ);
		final BlockPos end = new BlockPos(0, 64, endZ);
		rails.computeIfAbsent(start, key -> new HashMap<>()).put(end, new Rail(start, RailAngle.fromAngle(90), end, RailAngle.fromAngle(270), RailType.DIAMOND, TransportMode.TRAIN));
		rails.computeIfAbsent(end, key -> new HashMap<>()).put(start, new Rail(end, RailAngle.fromAngle(270), start, RailAngle.fromAngle(90), RailType.DIAMOND, TransportMode.TRAIN));
	}
}
