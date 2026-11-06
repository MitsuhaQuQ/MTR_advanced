package mtr.data;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Tuple;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which station a platform and a block belong to: the one set for the platform, wherever; else the one whose area takes it
 * in, at its height where the area has heights; with more than one, the one with a platform nearest to it, the height
 * counting more, as for an underground line under a main line.
 */
class PlatformStationTest {

	private static Station station(long id, int x1, int z1, int x2, int z2) {
		final Station station = new Station(id);
		station.corner1 = new Tuple<>(x1, z1);
		station.corner2 = new Tuple<>(x2, z2);
		return station;
	}

	private static Platform platform(long id, int x, int y, int z) {
		return new Platform(id, TransportMode.TRAIN, new BlockPos(x, y, z), new BlockPos(x + 10, y, z));
	}

	private static DataCache cache(List<Station> stations, List<Platform> platforms) {
		final DataCache dataCache = new DataCache(new LinkedHashSet<>(stations), new LinkedHashSet<>(platforms), new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>());
		dataCache.sync();
		return dataCache;
	}

	@Test
	void aPlatformSetToAStationBelongsToItWherever() {
		final Station a = station(1, 0, 0, 100, 100), b = station(2, 500, 500, 600, 600);
		final Platform platform = platform(10, 20, 64, 20);
		platform.stationId = 2;
		assertSame(b, cache(Arrays.asList(a, b), Collections.singletonList(platform)).platformIdToStation.get(10L));
		platform.stationId = 0;
		assertSame(a, cache(Arrays.asList(a, b), Collections.singletonList(platform)).platformIdToStation.get(10L));
		// The station set gone, by the area again
		platform.stationId = 99;
		assertSame(a, cache(Arrays.asList(a, b), Collections.singletonList(platform)).platformIdToStation.get(10L));
	}

	@Test
	void stationsOneAboveAnotherGoByTheNearestPlatform() {
		// The same area: an underground line at y 40 and a main line at y 70, each with a platform of its own set
		final Station underground = station(1, 0, 0, 200, 200), main = station(2, 0, 0, 200, 200);
		final Platform undergroundPlatform = platform(10, 50, 40, 50), mainPlatform = platform(11, 50, 70, 50);
		undergroundPlatform.stationId = 1;
		mainPlatform.stationId = 2;
		final Platform low = platform(12, 120, 42, 120), high = platform(13, 120, 68, 120);
		for (final List<Station> stations : Arrays.asList(Arrays.asList(underground, main), Arrays.asList(main, underground))) {
			final DataCache dataCache = cache(stations, Arrays.asList(high, mainPlatform, low, undergroundPlatform));
			assertSame(underground, dataCache.platformIdToStation.get(12L));
			assertSame(main, dataCache.platformIdToStation.get(13L));
			assertEquals(new TreeSet<>(Arrays.asList(12L, 13L)), dataCache.overlappingPlatforms.keySet());
			assertTrue(dataCache.platformsByIdAlone.isEmpty());
		}
	}

	@Test
	void withNothingToGoByTheLowestIdIsTakenAndTold() {
		final Station a = station(1, 0, 0, 100, 100), b = station(2, 0, 0, 100, 100);
		final DataCache dataCache = cache(Arrays.asList(b, a), Collections.singletonList(platform(10, 20, 64, 20)));
		assertSame(a, dataCache.platformIdToStation.get(10L));
		assertEquals(Collections.singleton(10L), dataCache.platformsByIdAlone);
	}

	@Test
	void heightsSetForTheAreasTellThemApart() {
		final Station underground = station(1, 0, 0, 200, 200), main = station(2, 0, 0, 200, 200);
		underground.setHeight(new int[]{0, 0, 200, 200}, new int[]{30, 45});
		main.setHeight(new int[]{200, 200, 0, 0}, new int[]{60, 80});
		// Nothing set for any platform, so only the heights tell
		final DataCache dataCache = cache(Arrays.asList(main, underground), Arrays.asList(platform(12, 120, 40, 120), platform(13, 120, 70, 120)));
		assertSame(underground, dataCache.platformIdToStation.get(12L));
		assertSame(main, dataCache.platformIdToStation.get(13L));
		assertTrue(dataCache.overlappingPlatforms.isEmpty());
		assertTrue(underground.inArea(5, 40, 5));
		assertFalse(underground.inArea(5, 70, 5));
	}

	@Test
	void aBlockGoesByThePlatformNextToIt() {
		final Station a = station(1, 0, 0, 100, 100), far = station(2, 900, 900, 950, 950);
		final Platform platform = platform(10, 20, 64, 20);
		platform.stationId = 2;
		final DataCache dataCache = cache(Arrays.asList(a, far), Collections.singletonList(platform));
		// Next to the platform set to the far station
		assertSame(far, dataCache.getStationAt(new BlockPos(22, 66, 21)));
		// Away from any platform, by the area
		assertSame(a, dataCache.getStationAt(new BlockPos(80, 64, 80)));
		assertNull(dataCache.getStationAt(new BlockPos(400, 64, 400)));
	}

	@Test
	void aBlockAwayFromPlatformsInStationsOneAboveAnotherGoesByTheNearestPlatform() {
		final Station underground = station(1, 0, 0, 200, 200), main = station(2, 0, 0, 200, 200);
		final Platform undergroundPlatform = platform(10, 50, 40, 50), mainPlatform = platform(11, 50, 70, 50);
		undergroundPlatform.stationId = 1;
		mainPlatform.stationId = 2;
		final DataCache dataCache = cache(Arrays.asList(main, underground), Arrays.asList(undergroundPlatform, mainPlatform));
		assertSame(underground, dataCache.getStationAt(new BlockPos(150, 43, 150)));
		assertSame(main, dataCache.getStationAt(new BlockPos(150, 72, 150)));
	}

	@Test
	void heightsAreSavedSentAndUpdated() throws IOException {
		final Station station = station(1, 0, 0, 100, 100);
		station.extraAreas.add(new int[]{200, 200, 300, 300});
		station.setHeight(new int[]{300, 300, 200, 200}, new int[]{70, 50});
		final List<FriendlyByteBuf> sent = new ArrayList<>();
		station.setAreaHeights(sent::add);

		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(station.messagePackLength());
		station.toMessagePack(packer);
		packer.close();
		final Map<String, Value> map = RailwayData.castMessagePackValueToSKMap(MessagePack.newDefaultUnpacker(packer.toByteArray()).unpackValue());
		assertArrayEquals(new int[]{50, 70}, new Station(map).getHeight(new int[]{200, 200, 300, 300}));
		assertNull(new Station(map).getHeight(new int[]{0, 0, 100, 100}));

		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		station.writePacket(packet);
		assertArrayEquals(new int[]{50, 70}, new Station(packet).getHeight(new int[]{200, 200, 300, 300}));

		final Station updated = station(1, 0, 0, 100, 100);
		updated.extraAreas.add(new int[]{200, 200, 300, 300});
		final FriendlyByteBuf update = sent.get(0);
		update.readLong();
		update.readUtf();
		updated.update(update.readUtf(), update);
		assertArrayEquals(new int[]{50, 70}, updated.getHeight(new int[]{200, 200, 300, 300}));

		// An old save has none: every height
		final Map<String, Value> old = new HashMap<>(map);
		old.remove("area_heights");
		assertFalse(new Station(old).hasHeights());
	}
}
