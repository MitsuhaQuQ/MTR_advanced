package mtr.data;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RouteEntriesTest {

	private static final BlockPos NODE_1 = new BlockPos(1, 64, 0);
	private static final BlockPos NODE_2 = new BlockPos(2, 64, 0);

	@Test
	void keyNodesKeepTheirPlaceBetweenStops() {
		final Route route = route(1, 2);
		final List<Object> entries = route.getEntries();
		entries.add(1, NODE_1);
		entries.add(NODE_2);
		route.setEntries(entries);

		assertEquals(Collections.singletonList(NODE_1), route.platformIds.get(0).waypoints);
		assertEquals(Collections.singletonList(NODE_2), route.platformIds.get(1).waypoints);
		assertEquals(entries, route.getEntries());
	}

	@Test
	void keyNodeBeforeTheFirstStopMovesAfterIt() {
		final Route route = route(1, 2);
		final List<Object> entries = route.getEntries();
		entries.add(0, NODE_1);
		route.setEntries(entries);

		assertEquals(Collections.singletonList(NODE_1), route.platformIds.get(0).waypoints);
		assertEquals(Arrays.asList(route.platformIds.get(0), NODE_1, route.platformIds.get(1)), route.getEntries());
	}

	@Test
	void deletingAStopKeepsItsKeyNodes() {
		final Route route = route(1, 2, 3);
		route.platformIds.get(0).waypoints.add(NODE_1);
		route.platformIds.get(1).waypoints.add(NODE_2);
		final List<Object> entries = route.getEntries();
		entries.remove(route.platformIds.get(1));
		route.setEntries(entries);

		assertEquals(2, route.platformIds.size());
		assertEquals(Arrays.asList(NODE_1, NODE_2), route.platformIds.get(0).waypoints);
	}

	private static Route route(long... platformIds) {
		final Route route = new Route(1, TransportMode.TRAIN);
		for (final long platformId : platformIds) {
			route.platformIds.add(new Route.RoutePlatform(platformId));
		}
		return route;
	}
}
