package mtr.data;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Tuple;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StationAreaTest {

	@Test
	void extraRectanglesCountAsTheStation() {
		final Station station = station(1, 1, 1, 10, 10);
		station.extraAreas.add(new int[]{100, 100, 110, 110});
		assertTrue(station.inArea(5, 5));
		assertTrue(station.inArea(105, 105));
		assertFalse(station.inArea(50, 50));
		assertEquals(2, station.getRectangles().size());
	}

	@Test
	void stationsConnectThroughExtraRectanglesAndCrossingShapes() {
		final Station station1 = station(1, 1, 1, 10, 10);
		station1.extraAreas.add(new int[]{100, 100, 110, 110});
		assertTrue(station1.intersecting(station(2, 105, 105, 120, 120)));
		assertTrue(station(3, 0, 4, 20, 6).intersecting(station(4, 8, 0, 10, 20)));
		assertFalse(station1.intersecting(station(5, 50, 50, 60, 60)));
	}

	@Test
	void chosenStationWinsOnlyInsideItsArea() {
		final Station farStation = station(1, 300, 300, 310, 310);
		farStation.extraAreas.add(new int[]{0, 0, 20, 20});
		final Station nearStation = station(2, 0, 0, 20, 20);
		final Station otherStation = station(3, 500, 500, 510, 510);
		final Platform platform = new Platform(10, TransportMode.TRAIN, new BlockPos(5, 64, 5), new BlockPos(5, 64, 15));

		final Set<Station> stations = new HashSet<>();
		stations.add(farStation);
		stations.add(nearStation);
		stations.add(otherStation);
		final DataCache dataCache = new DataCache(stations, Collections.singleton(platform), new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>());

		platform.stationId = nearStation.id;
		dataCache.sync();
		assertEquals(nearStation, dataCache.platformIdToStation.get(platform.id));

		platform.stationId = farStation.id;
		dataCache.sync();
		assertEquals(farStation, dataCache.platformIdToStation.get(platform.id));

		platform.stationId = otherStation.id;
		dataCache.sync();
		assertTrue(dataCache.platformIdToStation.get(platform.id) != otherStation);
	}

	private static Station station(long id, int x1, int z1, int x2, int z2) {
		final Station station = new Station(id);
		station.corner1 = new Tuple<>(x1, z1);
		station.corner2 = new Tuple<>(x2, z2);
		return station;
	}
}
