package transitsim.io;

/**
 * Where the component writes its data for saving or sending. The encodings are those of Minecraft's packet buffer, so
 * data the host saved before keeps reading the same: numbers big-endian, booleans as one byte, var-ints in groups of seven
 * bits, lowest first.
 */
public interface BinaryWriter {

	void writeByte(int value);

	void writeBoolean(boolean value);

	void writeInt(int value);

	void writeLong(long value);

	void writeVarInt(int value);
}
