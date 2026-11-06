package mtr.data;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.value.Value;
import transitsim.plan.Buffers;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How much longer than planned a depot's trains were measured to take (D1): the measurements kept in saves, the buffers
 * sent to the clients when they change, and cleared from the web editor.
 */
class MeasuredBuffersTest {

	private static Map<String, Value> saved(SerializedDataBase data) throws IOException {
		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(data.messagePackLength());
		data.toMessagePack(packer);
		packer.close();
		return new HashMap<>(RailwayData.castMessagePackValueToSKMap(MessagePack.newDefaultUnpacker(packer.toByteArray()).unpackValue()));
	}

	private static void readUpdate(Depot depot, FriendlyByteBuf update) {
		update.readLong();
		update.readUtf();
		depot.update(update.readUtf(), update);
	}

	@Test
	void buffersAreSavedSentAndCleared() throws IOException {
		final Depot depot = new Depot(1, TransportMode.TRAIN);
		final String key = Buffers.key(Buffers.TURN, 3, 4);
		for (int i = 0; i < 5; i++) {
			depot.bufferRecorder.add(key, 40000);
		}
		final List<FriendlyByteBuf> sent = new ArrayList<>();
		depot.sendBuffersIfChanged(sent::add);
		assertEquals(40000, depot.getBuffers().turn(3, 4));
		assertEquals(1, sent.size());
		// Not again while they stay the same
		depot.sendBuffersIfChanged(sent::add);
		assertEquals(1, sent.size());

		// The measurements are saved, so the buffers are the same after a restart
		final Depot loaded = new Depot(saved(depot));
		assertEquals(depot.getBuffers(), loaded.getBuffers());
		assertTrue(loaded.bufferRecorder.hasSamples());

		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		depot.writePacket(packet);
		assertEquals(depot.getBuffers(), new Depot(packet).getBuffers());

		final Depot client = new Depot(1, TransportMode.TRAIN);
		readUpdate(client, sent.get(0));
		assertEquals(depot.getBuffers(), client.getBuffers());

		// Cleared on a client, sent to the server, which forgets what it measured
		final List<FriendlyByteBuf> cleared = new ArrayList<>();
		client.clearBuffers(cleared::add);
		assertTrue(client.getBuffers().isEmpty());
		readUpdate(depot, cleared.get(0));
		assertTrue(depot.getBuffers().isEmpty());
		assertFalse(depot.bufferRecorder.hasSamples());
	}

	@Test
	void theServerKeepsItsOwnBuffersWhateverAPlayerSends() {
		final Depot server = new Depot(1, TransportMode.TRAIN);
		for (int i = 0; i < 5; i++) {
			server.bufferRecorder.add(Buffers.key(Buffers.OUT, 9, 3), 30000);
		}
		server.sendBuffersIfChanged(packet -> {
		});
		final Depot other = new Depot(1, TransportMode.TRAIN);
		for (int i = 0; i < 5; i++) {
			other.bufferRecorder.add(Buffers.key(Buffers.OUT, 9, 3), 90000);
		}
		final List<FriendlyByteBuf> sent = new ArrayList<>();
		other.sendBuffersIfChanged(sent::add);
		readUpdate(server, sent.get(0));
		assertEquals(30000, server.getBuffers().out(9, 3));
	}

	@Test
	void anOldDepotHasNoBuffers() throws IOException {
		final Depot depot = new Depot(1, TransportMode.TRAIN);
		final Map<String, Value> map = saved(depot);
		map.remove("measured_buffers");
		assertTrue(new Depot(map).getBuffers().isEmpty());
	}
}
