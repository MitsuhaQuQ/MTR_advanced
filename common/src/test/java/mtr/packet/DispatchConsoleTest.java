package mtr.packet;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The dispatcher's console snapshot (D6) reads back as {@link DispatchConsole#write} lays it out.
 */
class DispatchConsoleTest {

	@Test
	void aSnapshotReadsBackAsWritten() {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(1000);
		packet.writeVarInt(1);
		packet.writeLong(77);
		packet.writeVarInt(1);
		packet.writeLong(5);
		packet.writeLong(100);
		packet.writeLong(11);
		packet.writeVarInt(2);
		packet.writeLong(3);
		packet.writeLong(4);
		packet.writeLong(9);
		packet.writeLong(500);
		packet.writeLong(0);
		packet.writeVarInt(3);
		packet.writeVarInt(1);
		packet.writeVarInt(2);
		packet.writeVarInt(16);
		// Where it is by stops, for the run monitor
		packet.writeLong(8);
		packet.writeVarInt(1);
		final DispatchConsole.Snapshot snapshot = DispatchConsole.read(packet);
		assertEquals(1000, snapshot.serverMillis);
		assertEquals(Collections.singleton(77L), snapshot.occupiedBlocks);
		assertEquals(1, snapshot.trains.size());
		final DispatchConsole.TrainState train = snapshot.trains.get(0);
		assertEquals(5, train.trainId);
		assertEquals(100, train.sidingId);
		assertEquals(11, train.routeId);
		assertEquals(2, train.slot);
		assertEquals(new UUID(3, 4), train.headRail);
		assertEquals(9, train.nextStopId);
		assertEquals(500, train.lateSince);
		assertEquals(0, train.waitingSince);
		assertEquals(3, train.flags);
		assertEquals(1, train.turnState);
		assertEquals(2, train.takeoverState);
		assertEquals(16, train.dispatchState);
		assertEquals(8, train.lastStopId);
		assertEquals(1, train.stopState);
		assertEquals(0, packet.readableBytes());
	}
}
