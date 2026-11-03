package transitsim.io;

/**
 * Reads what a {@link BinaryWriter} wrote.
 */
public interface BinaryReader {

	byte readByte();

	int readUnsignedByte();

	boolean readBoolean();

	int readInt();

	long readLong();

	int readVarInt();
}
