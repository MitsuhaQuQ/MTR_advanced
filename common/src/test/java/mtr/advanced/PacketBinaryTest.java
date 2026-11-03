package mtr.advanced;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import transitsim.io.ByteArrayBinary;
import transitsim.plan.DepotDuties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The component writes saved data itself since S3a. It has to come out byte for byte as Minecraft's packet buffer wrote
 * it, or old saves wouldn't read.
 */
class PacketBinaryTest {

	@Test
	void writesTheSameBytesAsThePacketBuffer() {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		final ByteArrayBinary.Writer writer = new ByteArrayBinary.Writer();
		for (final int value : new int[]{0, 1, 127, 128, 300, 16383, 16384, Integer.MAX_VALUE, -1, Integer.MIN_VALUE}) {
			packet.writeVarInt(value);
			packet.writeInt(value);
			writer.writeVarInt(value);
			writer.writeInt(value);
		}
		packet.writeLong(Long.MIN_VALUE + 12345);
		packet.writeBoolean(true);
		packet.writeByte(200);
		writer.writeLong(Long.MIN_VALUE + 12345);
		writer.writeBoolean(true);
		writer.writeByte(200);
		final byte[] expected = new byte[packet.readableBytes()];
		packet.readBytes(expected);
		assertArrayEquals(expected, writer.toBytes());
	}

	@Test
	void readsWhatThePacketBufferWrote() {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeVarInt(-1);
		packet.writeVarInt(300);
		packet.writeLong(-42);
		packet.writeByte(250);
		final byte[] bytes = new byte[packet.readableBytes()];
		packet.readBytes(bytes);
		final ByteArrayBinary.Reader reader = new ByteArrayBinary.Reader(bytes);
		assertEquals(-1, reader.readVarInt());
		assertEquals(300, reader.readVarInt());
		assertEquals(-42, reader.readLong());
		assertEquals(250, reader.readUnsignedByte());
	}

	@Test
	void dutiesSaveAsBefore() {
		final DepotDuties duties = new DepotDuties();
		duties.diagramMode = true;
		final DepotDuties.Slot slot = new DepotDuties.Slot();
		final DepotDuties.DayDuty day = new DepotDuties.DayDuty();
		day.weekdays = 0b11111;
		day.stayOnLine = true;
		day.nextSlot = 2;
		day.segments.add(new DepotDuties.Segment(123456789L, 7 * 60 * 60 * 1000, 0, -1));
		day.segments.add(new DepotDuties.Segment(-5, 1000, 3, 4));
		slot.days.add(day);
		duties.setSlot(99, 3, 1, slot);

		// What the packet buffer writes is what was saved before
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		duties.write(PacketBinary.of(packet));
		final byte[] expected = new byte[packet.readableBytes()];
		packet.readBytes(expected);
		assertArrayEquals(expected, duties.toBytes());

		final DepotDuties read = new DepotDuties();
		read.fromBytes(expected);
		assertArrayEquals(expected, read.toBytes());
		assertEquals(2, read.getSlots(99).get(1).days.get(0).nextSlot);
	}
}
