package transitsim.io;

import java.io.IOException;
import java.util.List;

/**
 * Writes values by name, for a {@link KeyedReader} to read back. The host decides how they are stored.
 */
public interface KeyedWriter {

	void writeString(String key, String value) throws IOException;

	void writeInt(String key, int value) throws IOException;

	void writeBoolean(String key, boolean value) throws IOException;

	void writeInts(String key, int[] values) throws IOException;

	void writeLongs(String key, long[] values) throws IOException;

	void writeLongArrays(String key, List<long[]> values) throws IOException;
}
