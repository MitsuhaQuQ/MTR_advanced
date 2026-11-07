package mtr.data;

import java.util.Collection;
import java.util.Map;
import java.util.function.BiConsumer;

public interface RailwayDataStore {

	void forEach(String type, BiConsumer<Long, byte[]> consumer) throws Exception;

	void write(String type, long id, byte[] data) throws Exception;

	void delete(String type, long id) throws Exception;

	void commit() throws Exception;

	/**
	 * Keeps the revision of a part players edit (multiplayer edit conflicts), so it holds after the server is started
	 * again; {@code deleted} for an object deleted.
	 */
	default void writeEditRevision(String key, int revision, String editor, boolean deleted) throws Exception {
	}

	default void forEachEditRevision(EditRevisionConsumer consumer) throws Exception {
	}

	interface EditRevisionConsumer {

		void accept(String key, int revision, String editor, boolean deleted);
	}

	/**
	 * Drops what was written since the last commit, where the store can.
	 */
	default void rollback() throws Exception {
	}

	void backup();

	void close() throws Exception;

	/**
	 * Writes a version of the track section layer ({@link transitsim.network.TrackSections}): the sections added, those
	 * removed (kept until {@link #sweepTrackSections}, so the version before can still be read), the signal layer's rows,
	 * and last the root record that publishes the version. Only the SQLite store keeps it, in its own tables; the fallback
	 * of separate files doesn't.
	 */
	default void publishTrackSections(long version, Map<Long, byte[]> added, Collection<Long> removed, Map<Long, byte[]> overlayPut, Collection<Long> overlayRemoved, long fingerprint, int count) throws Exception {
	}

	/**
	 * Deletes the rows of the track sections removed in this published version or before.
	 */
	default void sweepTrackSections(long version) throws Exception {
	}

	/**
	 * Reads the track sections of the published version and the signal layer. Rows of a version never published, as when
	 * the server stopped while writing one, are dropped, and rows it removed are kept again.
	 *
	 * @return the published version as {version, fingerprint, count}, or null if none was ever published
	 */
	default long[] loadTrackSections(BiConsumer<Long, byte[]> sections, BiConsumer<Long, byte[]> overlay) throws Exception {
		return null;
	}

	/**
	 * @param fingerprint the network the path was worked out on, so a path from an older network can be told apart
	 */
	default void writeGraphPath(String owner, long fingerprint, byte[] data) throws Exception {
	}

	/**
	 * Replaces what the component's simulator needs of the world (S4e), which the day tool reads. Only the SQLite store
	 * keeps it.
	 */
	default void writeSimulationData(byte[] data) throws Exception {
	}
}
