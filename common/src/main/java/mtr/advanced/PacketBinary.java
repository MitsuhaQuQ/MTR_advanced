package mtr.advanced;

import net.minecraft.network.FriendlyByteBuf;
import transitsim.io.BinaryReader;
import transitsim.io.BinaryWriter;

/**
 * A packet buffer as the component's {@link BinaryWriter} and {@link BinaryReader}, so the component's data goes into
 * packets and saves byte for byte as before.
 */
public final class PacketBinary implements BinaryWriter, BinaryReader {

	private final FriendlyByteBuf packet;

	private PacketBinary(FriendlyByteBuf packet) {
		this.packet = packet;
	}

	public static PacketBinary of(FriendlyByteBuf packet) {
		return new PacketBinary(packet);
	}

	@Override
	public void writeByte(int value) {
		packet.writeByte(value);
	}

	@Override
	public void writeBoolean(boolean value) {
		packet.writeBoolean(value);
	}

	@Override
	public void writeInt(int value) {
		packet.writeInt(value);
	}

	@Override
	public void writeLong(long value) {
		packet.writeLong(value);
	}

	@Override
	public void writeVarInt(int value) {
		packet.writeVarInt(value);
	}

	@Override
	public byte readByte() {
		return packet.readByte();
	}

	@Override
	public int readUnsignedByte() {
		return packet.readUnsignedByte();
	}

	@Override
	public boolean readBoolean() {
		return packet.readBoolean();
	}

	@Override
	public int readInt() {
		return packet.readInt();
	}

	@Override
	public long readLong() {
		return packet.readLong();
	}

	@Override
	public int readVarInt() {
		return packet.readVarInt();
	}
}
