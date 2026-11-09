package mtr.data;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Tuple;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Depots one above another (2026-11-09): a depot's area set with the station area tool takes in its heights only, and a
 * siding goes to the depot taking in its height, as a platform to its station.
 */
class DepotAreaHeightsTest {

	@Test
	void stackedDepotsEachTakeInTheirOwnSidings() {
		// The same ground, a depot for the lower level (Y 40 to 60) and one for the upper (Y 90 to 110), and one with no
		// heights over all of it
		final Depot lower = depot(1, 40, 60);
		final Depot upper = depot(2, 90, 110);
		final Depot flat = depot(3, 0, 0);
		flat.clearAreaHeights();
		final Siding below = siding(11, 50);
		final Siding above = siding(12, 100);
		final Siding between = siding(13, 75);
		final DataCache dataCache = new DataCache(new HashSet<>(), new HashSet<>(), new HashSet<>(Arrays.asList(below, above, between)), new HashSet<>(), new LinkedHashSet<>(Arrays.asList(flat, lower, upper)), new HashSet<>());
		dataCache.sync();
		assertEquals(lower, dataCache.sidingIdToDepot.get(below.id));
		assertEquals(upper, dataCache.sidingIdToDepot.get(above.id));
		// At a height no depot with heights takes in: the first across, the one with none
		assertEquals(flat, dataCache.sidingIdToDepot.get(between.id));
		assertTrue(upper.inArea(5, 100, 5));
		assertFalse(upper.inArea(5, 50, 5));
		assertTrue(flat.inArea(5, 50, 5));
	}

	@Test
	void theHeightsAreSavedSentAndUpdated() throws IOException {
		final Depot depot = depot(1, 90, 110);
		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(depot.messagePackLength());
		depot.toMessagePack(packer);
		packer.close();
		final MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(packer.toByteArray());
		final Map<String, Value> map = new HashMap<>(RailwayData.castMessagePackValueToSKMap(unpacker.unpackValue()));
		assertFalse(unpacker.hasNext(), "every entry written is counted in the header");
		assertArrayEquals(new int[]{90, 110}, new Depot(map).getHeight(null));

		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		depot.writePacket(packet);
		assertArrayEquals(new int[]{90, 110}, new Depot(packet).getHeight(null));

		final List<FriendlyByteBuf> updates = new ArrayList<>();
		depot.sendAreaHeights(updates::add);
		final FriendlyByteBuf update = updates.get(0);
		update.readLong();
		update.readUtf();
		final Depot receiving = new Depot(1, TransportMode.TRAIN);
		receiving.update(update.readUtf(), update);
		assertArrayEquals(new int[]{90, 110}, receiving.getHeight(null));

		// Without heights, as before: every height
		final Depot flat = new Depot(2, TransportMode.TRAIN);
		final FriendlyByteBuf flatPacket = new FriendlyByteBuf(Unpooled.buffer());
		flat.writePacket(flatPacket);
		assertNull(new Depot(flatPacket).getHeight(null));
	}

	private static Depot depot(long id, int y1, int y2) {
		final Depot depot = new Depot(id, TransportMode.TRAIN);
		depot.corner1 = new Tuple<>(-100, -100);
		depot.corner2 = new Tuple<>(100, 100);
		depot.setAreaHeights(y1, y2);
		return depot;
	}

	private static Siding siding(long id, int y) {
		return new Siding(id, TransportMode.TRAIN, new BlockPos(0, y, 0), new BlockPos(0, y, 40), 20);
	}
}
