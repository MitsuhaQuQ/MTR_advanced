package mtr.data;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
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
	 * Writes what changed of the leg layer: legs and the owners of track sections, by key. Legs are checked against the
	 * track sections when read back, so they need no version of their own.
	 */
	default void writeLegLayer(Map<Long, byte[]> legsPut, Collection<Long> legsRemoved, Map<Long, byte[]> ownersPut, Collection<Long> ownersRemoved, Map<Long, byte[]> ownerSetsPut) throws Exception {
	}

	/**
	 * @param owners    each section's set of owners, by the section's hash
	 * @param ownerSets each set of owners, by its ID
	 */
	default void loadLegLayer(BiConsumer<Long, byte[]> legs, BiConsumer<Long, byte[]> owners, BiConsumer<Long, byte[]> ownerSets) throws Exception {
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

	/**
	 * @return whether paths can be saved as references to shared pieces ({@link PathPieces}); only the SQLite store keeps
	 * them, in tables of their own
	 */
	default boolean storesPathPieces() {
		return false;
	}

	/**
	 * @param pieces each piece, by its key
	 * @param refs   each owner and a piece it refers to
	 */
	default void loadPathPieces(BiConsumer<Long, byte[]> pieces, BiConsumer<Long, Long> refs) throws Exception {
	}

	/**
	 * Adds pieces; one already there is kept as it is.
	 */
	default void writePathPieces(Map<Long, byte[]> pieces) throws Exception {
	}

	/**
	 * Replaces the pieces an owner refers to; none drops the owner.
	 */
	default void writePathPieceRefs(long owner, Collection<Long> pieces) throws Exception {
	}

	default void deletePathPieces(Collection<Long> pieces) throws Exception {
	}

	/**
	 * @return whether depots' duties are also kept as layers in tables of their own, each body and group once by content,
	 * with indexes (运用表复用和索引, 运用补完 step 1); only the SQLite store does. The depot's entry stays what is read.
	 */
	default boolean storesDutyLayers() {
		return false;
	}

	/**
	 * @param fingerprints each depot and the fingerprint of the duties its layers were written from
	 */
	default void loadDutyFingerprints(BiConsumer<Long, Long> fingerprints) throws Exception {
	}

	/**
	 * Replaces a depot's assignments with those of its duties set by players ({@code user}) and planned ({@code auto}),
	 * adding the bodies and groups not stored yet.
	 */
	default void writeDutyLayers(long depotId, long fingerprint, transitsim.plan.DutyLayers user, transitsim.plan.DutyLayers auto) throws Exception {
	}

	default void deleteDutyLayers(long depotId) throws Exception {
	}

	/**
	 * Drops the groups no assignment refers to, and the bodies no group does.
	 */
	default void sweepDutyLayers() throws Exception {
	}

	/**
	 * @return each duty running a service, as {depot, 0 for one set by players or 1 planned, siding, slot, weekdays, duty
	 * number}
	 */
	default List<long[]> findDutiesRunning(long routeId, int service) throws Exception {
		return Collections.emptyList();
	}

	/**
	 * @return the depots with a duty of a group, by the group's key
	 */
	default List<Long> findDepotsUsingGroup(long groupKey) throws Exception {
		return Collections.emptyList();
	}
}
