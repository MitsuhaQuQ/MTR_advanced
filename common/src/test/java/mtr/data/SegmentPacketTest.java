package mtr.data;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import transitsim.motion.RunSegment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The run segment a train is on, as the server sends it to the clients (G2).
 */
class SegmentPacketTest {

	@Test
	void aSegmentComesThroughAsItWas() {
		final RunSegment.Spec spec = new RunSegment.Spec(7, 1_762_000_000_123L, 412.25, 0.875, 1.5, 980.5);
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		Train.writeSegmentSpec(packet, spec);
		Train.writeSegmentSpec(packet, null);
		packet.writeInt(42);
		assertEquals(spec, Train.readSegmentSpec(packet));
		assertNull(Train.readSegmentSpec(packet));
		// Nothing more is read than was written
		assertEquals(42, packet.readInt());
		assertFalse(packet.isReadable());
	}
}
