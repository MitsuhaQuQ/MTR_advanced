package mtr.data;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the web editor sets for a siding stays as it set it (5c): the trains it holds are its limit too, and the game's
 * siding screen, which now only sets its number and colour, leaves them alone.
 */
class SidingSettingsTest {

	private static Siding siding() {
		return new Siding(5, TransportMode.TRAIN, new BlockPos(0, 64, 0), new BlockPos(40, 64, 0), 40);
	}

	private static void apply(Siding siding, FriendlyByteBuf packet) {
		packet.readLong();
		packet.readUtf();
		siding.update(packet.readUtf(), packet);
	}

	@Test
	void theTrainsItHoldsAreItsLimit() {
		final Siding siding = siding();
		final List<FriendlyByteBuf> sent = new ArrayList<>();
		siding.setOperation(Collections.emptyList(), 3, sent::add);
		assertEquals(3, siding.getFleetSize());
		assertEquals(3, siding.getTrainLimit());
		assertFalse(siding.getUnlimitedTrains());

		final Siding other = siding();
		apply(other, sent.get(0));
		assertEquals(3, other.getFleetSize());
		assertEquals(3, other.getTrainLimit());

		siding.setOperation(Collections.emptyList(), -1, packet -> {
		});
		assertTrue(siding.getUnlimitedTrains());
		assertEquals(-1, siding.getTrainLimit());

		siding.setOperation(Collections.emptyList(), 0, packet -> {
		});
		assertEquals(0, siding.getTrainLimit());
	}

	@Test
	void aNewNumberLeavesWhatTheWebSet() {
		final Siding siding = siding();
		siding.setOperation(Collections.singletonList(11L), 4, packet -> {
		});
		final List<FriendlyByteBuf> sent = new ArrayList<>();
		siding.name = "7";
		final Consumer<FriendlyByteBuf> send = sent::add;
		siding.setNameColor(send);

		final Siding other = siding();
		other.setOperation(Collections.singletonList(11L), 4, packet -> {
		});
		apply(other, sent.get(0));
		assertEquals("7", other.name);
		assertEquals(4, other.getFleetSize());
		assertEquals(4, other.getTrainLimit());
	}
}
