package mtr.data;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The check of lengths when a route's stops are put together (optimisation round 3b, step 3).
 */
class StopLengthsTest {

	@Test
	void aTrainLongerThanAPlatformItStopsAtIsPointedOutUnlessAllowed() {
		final DataCache dataCache = new DataCache(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>());
		final Route route = new Route(1, TransportMode.TRAIN);
		// Platforms taking trains of 200, 100, 100 and 200 blocks: their nodes are one less apart
		final int[] lengths = {199, 99, 99, 199};
		int z = 0;
		for (int i = 0; i < lengths.length; i++) {
			final Platform platform = new Platform(i + 1, TransportMode.TRAIN, new BlockPos(0, 64, z), new BlockPos(0, 64, z + lengths[i]));
			dataCache.platformIdMap.put(platform.id, platform);
			route.platformIds.add(new Route.RoutePlatform(platform.id));
			z += lengths[i] + 500;
		}
		assertEquals(100, StopLengths.getLength(dataCache.platformIdMap.get(2L)), 1E-6);

		// A 160 block train: the two short platforms
		assertEquals(Arrays.asList(1, 2), StopLengths.getTooShort(route, dataCache, 160));
		// Passed, a stop doesn't count; allowed by hand, neither
		route.platformIds.get(1).pass = true;
		route.platformIds.get(2).allowLonger = true;
		assertTrue(StopLengths.getTooShort(route, dataCache, 160).isEmpty());
		// A train that fits, or none running the route
		route.platformIds.get(1).pass = false;
		assertTrue(StopLengths.getTooShort(route, dataCache, 100).isEmpty());
		assertTrue(StopLengths.getTooShort(route, dataCache, 0).isEmpty());
		assertEquals(0, StopLengths.getLongestTrain(route, dataCache, Collections.emptySet()));
	}
}
