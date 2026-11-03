package transitsim.io;

import java.util.List;

/**
 * Reads values saved by name, as a {@link KeyedWriter} wrote them. Missing names give the default, or an empty array.
 */
public interface KeyedReader {

	String getString(String key, String defaultValue);

	int getInt(String key, int defaultValue);

	long getLong(String key, long defaultValue);

	boolean getBoolean(String key, boolean defaultValue);

	long[] getLongs(String key);

	List<long[]> getLongArrays(String key);
}
