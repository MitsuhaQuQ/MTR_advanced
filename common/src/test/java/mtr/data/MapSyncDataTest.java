package mtr.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MapSyncDataTest {

	@Test
	void railHashIgnoresOrderButNotContent() {
		final long[] rails = {1, 2, 0, 3, 4, 0};
		assertEquals(MapSyncData.hashRails(rails), MapSyncData.hashRails(new long[]{3, 4, 0, 1, 2, 0}));
		assertNotEquals(MapSyncData.hashRails(rails), MapSyncData.hashRails(new long[]{1, 2, 0, 3, 5, 0}));
		assertNotEquals(MapSyncData.hashRails(rails), MapSyncData.hashRails(new long[]{1, 2, 0}));
		assertNotEquals(MapSyncData.hashRails(rails), MapSyncData.hashRails(new long[]{1, 2, 1, 3, 4, 0}));
	}

	@Test
	void routeHashDependsOnOrder() {
		assertNotEquals(MapSyncData.hashRoute(new long[]{1, 2, 3}), MapSyncData.hashRoute(new long[]{3, 2, 1}));
	}

	@Test
	void regionsSplitNegativeCoordinatesCleanly() {
		assertEquals(MapSyncData.regionKey(0, 0), MapSyncData.regionKey(127, 127));
		assertNotEquals(MapSyncData.regionKey(0, 0), MapSyncData.regionKey(-1, 0));
		assertNotEquals(MapSyncData.regionKey(0, 0), MapSyncData.regionKey(0, -1));
		assertEquals(MapSyncData.regionKey(-1, -1), MapSyncData.regionKey(-128, -128));
	}
}
