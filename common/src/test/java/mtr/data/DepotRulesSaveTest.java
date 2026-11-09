package mtr.data;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;
import transitsim.plan.DepotRules;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A depot's rules for going out and back (运用补完 step 2) in its save, its packets and an update from the editor; and its
 * save's entries counted right, which they weren't for the takeovers before.
 */
class DepotRulesSaveTest {

	private static DepotRules rules() {
		final DepotRules rules = new DepotRules();
		final DepotRules.Rule rule = new DepotRules.Rule();
		rule.id = 3;
		rule.lineKeys.add("TRAIN|line 1");
		rule.outEntrance = 8685662602671542691L;
		rule.backEntrance = 8685662602671542691L;
		rule.extraLeadMillis = 30000;
		rules.rules.add(rule);
		return rules;
	}

	/**
	 * @return what a save holds, read as a game reads it, failing if the header's count isn't what was written
	 */
	private static Map<String, Value> saved(Depot depot, boolean reduced) throws IOException {
		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(reduced ? depot.reducedMessagePackLength() : depot.messagePackLength());
		if (reduced) {
			depot.toReducedMessagePack(packer);
		} else {
			depot.toMessagePack(packer);
		}
		packer.close();
		final MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(packer.toByteArray());
		final Map<String, Value> map = new HashMap<>(RailwayData.castMessagePackValueToSKMap(unpacker.unpackValue()));
		assertFalse(unpacker.hasNext(), "every entry written is counted in the header");
		return map;
	}

	@Test
	void rulesAreSavedSentAndUpdated() throws IOException {
		final Depot depot = new Depot(1, TransportMode.TRAIN);
		depot.rules.fromBytes(rules().toBytes());
		// Saved: the rules, and the takeovers now read back
		final Map<String, Value> full = saved(depot, false);
		assertTrue(full.containsKey("duty_takeovers"), "the takeovers are within the save's entries");
		final Depot read = new Depot(full);
		assertArrayEquals(rules().toBytes(), read.rules.toBytes());
		assertArrayEquals(rules().toBytes(), new Depot(saved(depot, true)).rules.toBytes());
		// Sent to the clients
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		depot.writePacket(packet);
		final Depot sent = new Depot(packet);
		assertArrayEquals(rules().toBytes(), sent.rules.toBytes());
		// Changed from the editor
		final Depot other = new Depot(1, TransportMode.TRAIN);
		final List<FriendlyByteBuf> updates = new ArrayList<>();
		other.setRules(rules(), updates::add);
		final FriendlyByteBuf update = updates.get(0);
		update.readLong();
		update.readUtf();
		final Depot receiving = new Depot(1, TransportMode.TRAIN);
		receiving.update(update.readUtf(), update);
		assertArrayEquals(rules().toBytes(), receiving.rules.toBytes());
	}
}
