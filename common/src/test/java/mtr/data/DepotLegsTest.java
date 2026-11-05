package mtr.data;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a siding's trains get out and back reaches the clients as it was worked out.
 */
class DepotLegsTest {

	@Test
	void legsAreSentAsTheyAre() {
		final Map<Long, Long> out = new HashMap<>();
		out.put(11L, 45000L);
		out.put(12L, 90000L);
		final DepotLegs legs = new DepotLegs(11, out, Collections.singletonMap(12L, 60000L));
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		legs.writePacket(packet);
		final DepotLegs read = DepotLegs.readPacket(packet);
		assertEquals(11, read.entrancePlatform);
		assertEquals(legs.outMillis, read.outMillis);
		assertEquals(legs.backMillis, read.backMillis);
	}

	@Test
	void noPathsMeansNoLegs() {
		final DepotLegs legs = DepotLegs.of(Collections.emptyList());
		assertEquals(0, legs.entrancePlatform);
		assertTrue(legs.outMillis.isEmpty());
	}
}
