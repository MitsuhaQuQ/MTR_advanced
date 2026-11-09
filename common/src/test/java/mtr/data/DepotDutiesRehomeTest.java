package mtr.data;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Duties set for a depot are its pools' (运用补完 step 5): a siding taken away hands its slots on to another of its
 * pool on the server, which tells the players, and the save keeps where they went.
 */
class DepotDutiesRehomeTest {

	@Test
	void aSidingTakenAwayHandsItsDutiesOnAndThePlayersAreTold() throws IOException {
		final Siding first = siding(101, 0, "emu");
		final Siding second = siding(102, 10, "emu");
		final Siding other = siding(103, 20, "dmu");
		assertEquals(first.getPoolKey(), second.getPoolKey());
		assertNotEquals(first.getPoolKey(), other.getPoolKey());

		final Depot depot = new Depot(1, TransportMode.TRAIN);
		depot.duties.diagramMode = true;
		depot.duties.setSlot(first.id, 2, -1, null);
		depot.duties.setSlot(second.id, 1, -1, null);
		depot.duties.setSlot(other.id, 1, -1, null);
		final List<FriendlyByteBuf> sent = new ArrayList<>();
		pools(depot, first, second, other);
		depot.rehomeDuties(sent::add);
		assertTrue(sent.isEmpty(), "nothing gone, nothing sent");
		assertEquals(first.getPoolKey(), depot.duties.sidingPools.get(first.id));

		// The first siding is gone: its two slots go to the second, the other vehicle's stays where it is
		final long[] ids = {depot.duties.getSlots(first.id).get(0).id, depot.duties.getSlots(first.id).get(1).id};
		pools(depot, second, other);
		depot.rehomeDuties(sent::add);
		assertEquals(3, depot.duties.getSlots(second.id).size());
		assertEquals(1, depot.duties.getSlots(other.id).size());
		assertEquals(2, depot.duties.moves.size());
		assertNotNull(depot.duties.findSlot(ids[0]));
		assertEquals(1, sent.size());
		// Looked at once: the same sidings again change nothing and send nothing
		depot.rehomeDuties(sent::add);
		assertEquals(1, sent.size());

		// A player's copy takes the update, keeping its own mode
		final FriendlyByteBuf update = sent.get(0);
		assertEquals(depot.id, update.readLong());
		update.readUtf();
		final Depot client = new Depot(1, TransportMode.TRAIN);
		client.duties.diagramMode = true;
		client.update(update.readUtf(), update);
		assertArrayEquals(depot.duties.toBytes(), client.duties.toBytes());

		// Saved and sent whole, where they went is kept
		final Depot read = new Depot(saved(depot));
		assertArrayEquals(depot.duties.toBytes(), read.duties.toBytes());
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		depot.writePacket(packet);
		assertArrayEquals(depot.duties.toBytes(), new Depot(packet).duties.toBytes());
	}

	private static Siding siding(long id, int x, String vehicle) {
		final Siding siding = new Siding(id, TransportMode.TRAIN, new BlockPos(x, 64, 0), new BlockPos(x, 64, 200), 20);
		siding.setTrainIdAndBaseType(vehicle, "train_19_2", packet -> {
		});
		return siding;
	}

	private static void pools(Depot depot, Siding... sidings) {
		depot.clearPools();
		for (final Siding siding : sidings) {
			depot.addToPool(siding);
		}
	}

	private static Map<String, Value> saved(Depot depot) throws IOException {
		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(depot.messagePackLength());
		depot.toMessagePack(packer);
		packer.close();
		final MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(packer.toByteArray());
		return new HashMap<>(RailwayData.castMessagePackValueToSKMap(unpacker.unpackValue()));
	}
}
