package mtr.data;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The network's settings (optimisation round 4): saved and sent whole, an old save having the defaults, and kept within
 * bounds however they come in.
 */
class NetworkSettingsTest {

	@Test
	void theSettingsAreSavedAndSent() throws IOException {
		final NetworkSettings settings = new NetworkSettings();
		assertEquals(30000, settings.minReverseMillis);
		settings.minReverseMillis = 45000;
		settings.earlyThresholdMillis = 60000;
		// The wait thresholds of the network and of one station (运用补完 step 2)
		settings.waitThresholdMillis = 420000;
		settings.stationWaitThresholds.put(77L, 900000L);
		assertEquals(900000, settings.getWaitThreshold(77));
		assertEquals(420000, settings.getWaitThreshold(78));

		final MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
		packer.packMapHeader(settings.messagePackLength());
		settings.toMessagePack(packer);
		packer.close();
		final Map<String, Value> map = RailwayData.castMessagePackValueToSKMap(MessagePack.newDefaultUnpacker(packer.toByteArray()).unpackValue());
		assertEquals(settings, new NetworkSettings(map));

		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		settings.writePacket(packet);
		final NetworkSettings read = new NetworkSettings();
		read.read(packet);
		assertEquals(settings, read);

		// An old save has none: the defaults
		final Map<String, Value> old = new HashMap<>(map);
		old.remove("min_reverse_millis");
		assertEquals(NetworkSettings.DEFAULT_MIN_REVERSE_MILLIS, new NetworkSettings(old).minReverseMillis);
	}

	@Test
	void theSettingsStayWithinBounds() {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(-5);
		packet.writeLong(Long.MAX_VALUE);
		packet.writeLong(Long.MAX_VALUE);
		packet.writeVarInt(1);
		packet.writeLong(77);
		packet.writeLong(-1);
		final NetworkSettings read = new NetworkSettings();
		read.read(packet);
		assertEquals(0, read.minReverseMillis);
		assertEquals(NetworkSettings.MAX_MILLIS, read.earlyThresholdMillis);
		assertEquals(NetworkSettings.MAX_WAIT_THRESHOLD_MILLIS, read.waitThresholdMillis);
		assertEquals(0, read.getWaitThreshold(77));
	}
}
