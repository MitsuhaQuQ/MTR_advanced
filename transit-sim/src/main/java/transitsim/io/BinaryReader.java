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

	/**
	 * @return whether there is anything left to read, as far as the reader knows; false if it can't tell
	 */
	default boolean hasMore() {
		return false;
	}
}
