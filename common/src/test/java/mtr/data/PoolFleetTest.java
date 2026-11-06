package mtr.data;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The trains a depot gives each pool of its sidings, and the trains a siding keeps in its shed (P2-C): kept in saves,
 * packets and updates, an old save's pool having its sidings' fleets added up.
 */
class PoolFleetTest {

	private static Siding siding(int fleet) {
		final Siding siding = new Siding(TransportMode.TRAIN, new BlockPos(0, 64, 0), new BlockPos(40, 64, 0), 40);
		siding.setOperation(Collections.emptyList(), fleet, packet -> {
		});
		return siding;
	}

	private static Map<String, Value> saved(SerializedDataBase data) throws IOException {
		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(data.messagePackLength());
		data.toMessagePack(packer);
		packer.close();
		return new HashMap<>(RailwayData.castMessagePackValueToSKMap(MessagePack.newDefaultUnpacker(packer.toByteArray()).unpackValue()));
	}

	@Test
	void aPoolLeftOutHasItsSidingsFleetsAddedUp() {
		final Depot depot = new Depot(1, TransportMode.TRAIN);
		assertEquals(7, depot.getPoolFleet("train/2", Arrays.asList(siding(3), siding(4))));
		assertEquals(-1, depot.getPoolFleet("train/2", Arrays.asList(siding(3), siding(-1))), "one siding with as many as needed");
		depot.poolFleets.put("train/2", 5);
		assertEquals(5, depot.getPoolFleet("train/2", Arrays.asList(siding(3), siding(4))));
	}

	@Test
	void poolFleetsAreSavedSentAndUpdated() throws IOException {
		final Depot depot = new Depot(1, TransportMode.TRAIN);
		final List<FriendlyByteBuf> sent = new ArrayList<>();
		final Map<String, Integer> fleets = new TreeMap<>();
		fleets.put("train/2", 6);
		fleets.put("other/4", -1);
		depot.setPoolFleets(fleets, sent::add);
		assertEquals(fleets, depot.poolFleets);

		assertEquals(fleets, new Depot(saved(depot)).poolFleets);

		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		depot.writePacket(packet);
		assertEquals(fleets, new Depot(packet).poolFleets);

		final Depot updated = new Depot(1, TransportMode.TRAIN);
		final FriendlyByteBuf update = sent.get(0);
		update.readLong();
		update.readUtf();
		updated.update(update.readUtf(), update);
		assertEquals(fleets, updated.poolFleets);
	}

	@Test
	void anOldDepotHasNoPoolFleets() throws IOException {
		final Depot depot = new Depot(1, TransportMode.TRAIN);
		depot.poolFleets.put("train/2", 6);
		final Map<String, Value> map = saved(depot);
		map.remove("pool_fleets");
		assertTrue(new Depot(map).poolFleets.isEmpty());
	}

	@Test
	void theTrainsKeptInAShedAreSavedWithTheirIds() throws Exception {
		// A train has its cargo, which needs the game's registries
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		final Siding siding = siding(4);
		final TrainServer kept = new TrainServer(77, siding.id, 40, "train", "train_24_2", 2, new ArrayList<>(), new ArrayList<>(), 0, 0, Train.ACCELERATION_DEFAULT, new ArrayList<>(), false, 0, 0);
		final java.lang.reflect.Field shed = Siding.class.getDeclaredField("arriving");
		shed.setAccessible(true);
		@SuppressWarnings("unchecked") final List<TrainServer> arriving = (List<TrainServer>) shed.get(siding);
		arriving.add(kept);
		assertEquals(1, siding.getShedCount());
		siding.keepArrived();
		assertEquals(1, siding.getShedCount());
		assertEquals(1, siding.getHeldCount());

		final Siding read = new Siding(saved(siding));
		assertEquals(1, read.getShedCount());
		final Map<Long, TrainServer> index = new HashMap<>();
		read.indexTrains(index);
		assertTrue(index.containsKey(77L), index.keySet().toString());
		assertEquals(read.id, index.get(77L).sidingId);
	}
}
