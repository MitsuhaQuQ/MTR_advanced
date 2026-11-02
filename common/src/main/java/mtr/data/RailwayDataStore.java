package mtr.data;

import java.util.Map;
import java.util.function.BiConsumer;

public interface RailwayDataStore {

	void forEach(String type, BiConsumer<Long, byte[]> consumer) throws Exception;

	void write(String type, long id, byte[] data) throws Exception;

	void delete(String type, long id) throws Exception;

	void commit() throws Exception;

	void backup();

	void close() throws Exception;

	/**
	 * Replaces the compressed rail network (see {@link mtr.advanced.PhysicalGraph}). Only the SQLite store keeps it, in its
	 * own tables; the fallback of separate files doesn't.
	 */
	default void replaceGraphSections(Map<Long, byte[]> sections, long fingerprint) throws Exception {
	}

	/**
	 * @param fingerprint the network the path was worked out on, so a path from an older network can be told apart
	 */
	default void writeGraphPath(String owner, long fingerprint, byte[] data) throws Exception {
	}

	default void forEachGraphSection(BiConsumer<Long, byte[]> consumer) throws Exception {
	}

	default long getGraphFingerprint() throws Exception {
		return 0;
	}
}
