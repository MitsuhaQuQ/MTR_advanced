package mtr.data;

import org.sqlite.JDBC;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class RailwayDataSqliteStore implements RailwayDataStore {

	private boolean hasPendingChanges;
	private boolean closed;

	private final Path savePath;
	private final Path databasePath;
	private final Path backupsPath;
	private final Connection connection;
	private final PreparedStatement selectStatement;
	private final PreparedStatement writeStatement;
	private final PreparedStatement deleteStatement;
	private final AtomicBoolean backupRunning = new AtomicBoolean();
	private final ExecutorService backupExecutor = Executors.newSingleThreadExecutor(runnable -> {
		final Thread thread = new Thread(runnable, "MTR Data Backup");
		thread.setDaemon(true);
		return thread;
	});

	private static final String BACKUPS_FOLDER = "backups";
	private static final String LEGACY_FOLDER = "legacy-files";
	private static final String BACKUP_EXTENSION = ".db";
	private static final String TEMPORARY_EXTENSION = ".tmp";
	private static final String KEY_MIGRATED = "migrated";
	/**
	 * The version of how the data is stored. A database of a later version, written by a newer jar, isn't opened, so this
	 * jar can't write over what it doesn't understand.
	 */
	private static final String KEY_SCHEMA_VERSION = "schema_version";
	private static final int SCHEMA_VERSION = 1;
	private static final String KEY_TRACK_VERSION = "track_version";
	private static final String KEY_TRACK_FINGERPRINT = "track_fingerprint";
	private static final String KEY_TRACK_COUNT = "track_count";
	private static final long COMPACT_MIN_FREE_BYTES = 64L * 1024 * 1024;
	private static final long BACKUP_INTERVAL_MILLIS = 60 * 60 * 1000;
	private static final String RESTORE_REQUEST = "restore.txt";
	private static final int BACKUP_WAIT_SECONDS = 60;

	public RailwayDataSqliteStore(Path savePath, String[] types) throws Exception {
		this.savePath = savePath;
		databasePath = savePath.resolve(RailwayDataFileStore.DATABASE_NAME);
		backupsPath = savePath.resolve(BACKUPS_FOLDER);
		Files.createDirectories(backupsPath);

		restoreRequested();
		if (Files.exists(databasePath) && !isHealthy(databasePath)) {
			System.out.println("Minecraft Transit Railway database is corrupted: " + databasePath);
			quarantineDatabase();
			restoreLatestBackup();
		} else if (Files.exists(databasePath)) {
			// Every start keeps the data as it was, before anything of this run can change it
			try {
				createBackup();
			} catch (Exception e) {
				System.out.println("Minecraft Transit Railway could not back up " + databasePath + " on starting");
				e.printStackTrace();
			}
		}

		connection = connect(databasePath);
		try (final Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA journal_mode=WAL");
			// Each commit reaches the disk before it counts, so a power cut loses nothing committed
			statement.execute("PRAGMA synchronous=FULL");
			statement.execute("CREATE TABLE IF NOT EXISTS entries (type TEXT NOT NULL, id INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY (type, id)) WITHOUT ROWID");
			statement.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
			// Our own tables for the physical network, apart from the entries of the data itself
			statement.execute("CREATE TABLE IF NOT EXISTS graph_sections (id INTEGER PRIMARY KEY, data BLOB NOT NULL)");
			// The track section layer by content hash: the version each row came in and the one that removed it, so a
			// version is read whole even when a later one was only partly written
			statement.execute("CREATE TABLE IF NOT EXISTS track_sections (id INTEGER PRIMARY KEY, data BLOB NOT NULL, added_in INTEGER NOT NULL, removed_in INTEGER)");
			statement.execute("CREATE TABLE IF NOT EXISTS signal_overlay (id INTEGER PRIMARY KEY, data BLOB NOT NULL)");
			statement.execute("CREATE TABLE IF NOT EXISTS track_legs (id INTEGER PRIMARY KEY, data BLOB NOT NULL)");
			statement.execute("CREATE TABLE IF NOT EXISTS track_ownership (id INTEGER PRIMARY KEY, data BLOB NOT NULL)");
			statement.execute("CREATE TABLE IF NOT EXISTS track_owner_sets (id INTEGER PRIMARY KEY, data BLOB NOT NULL)");
			statement.execute("CREATE TABLE IF NOT EXISTS graph_paths (owner TEXT PRIMARY KEY, graph_fingerprint INTEGER NOT NULL, data BLOB NOT NULL)");
			statement.execute("CREATE TABLE IF NOT EXISTS sim_data (key TEXT PRIMARY KEY, data BLOB NOT NULL)");
			// Pieces of paths by content hash, and which owner (a siding) refers to which, so a piece is stored once and
			// swept when no one refers to it (optimisation round 5, step 2)
			statement.execute("CREATE TABLE IF NOT EXISTS path_pieces (id INTEGER PRIMARY KEY, data BLOB NOT NULL)");
			statement.execute("CREATE TABLE IF NOT EXISTS path_piece_refs (owner INTEGER NOT NULL, piece INTEGER NOT NULL, PRIMARY KEY (owner, piece)) WITHOUT ROWID");
			statement.execute("CREATE INDEX IF NOT EXISTS path_piece_refs_piece ON path_piece_refs (piece)");
			// Depots' duties as layers (运用表复用和索引): each body and group once by content, the assignments of each depot
			// referring to them, and indexes from a group to the depots using it and from a service to the bodies running it
			statement.execute("CREATE TABLE IF NOT EXISTS duty_bodies (id INTEGER PRIMARY KEY, data BLOB NOT NULL)");
			statement.execute("CREATE TABLE IF NOT EXISTS duty_groups (id INTEGER PRIMARY KEY, pool TEXT NOT NULL, data BLOB NOT NULL)");
			statement.execute("CREATE TABLE IF NOT EXISTS duty_group_bodies (grp INTEGER NOT NULL, position INTEGER NOT NULL, body INTEGER NOT NULL, PRIMARY KEY (grp, position)) WITHOUT ROWID");
			statement.execute("CREATE INDEX IF NOT EXISTS duty_group_bodies_body ON duty_group_bodies (body)");
			statement.execute("CREATE TABLE IF NOT EXISTS duty_assignments (depot INTEGER NOT NULL, kind INTEGER NOT NULL, siding INTEGER NOT NULL, slot INTEGER NOT NULL, weekdays INTEGER NOT NULL, duty INTEGER NOT NULL, grp INTEGER NOT NULL, body INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY (depot, kind, siding, slot, weekdays)) WITHOUT ROWID");
			statement.execute("CREATE INDEX IF NOT EXISTS duty_assignments_grp ON duty_assignments (grp)");
			statement.execute("CREATE INDEX IF NOT EXISTS duty_assignments_body ON duty_assignments (body)");
			statement.execute("CREATE TABLE IF NOT EXISTS duty_body_services (route INTEGER NOT NULL, service INTEGER NOT NULL, body INTEGER NOT NULL, PRIMARY KEY (route, service, body)) WITHOUT ROWID");
			statement.execute("CREATE INDEX IF NOT EXISTS duty_body_services_body ON duty_body_services (body)");
			statement.execute("CREATE TABLE IF NOT EXISTS duty_depots (depot INTEGER PRIMARY KEY, fingerprint INTEGER NOT NULL)");
			// The revision of each part players edit, kept so edits are checked the same after the server is started again
			statement.execute("CREATE TABLE IF NOT EXISTS edit_revisions (key TEXT PRIMARY KEY, revision INTEGER NOT NULL, editor TEXT NOT NULL, deleted INTEGER NOT NULL)");
		}
		connection.setAutoCommit(false);

		selectStatement = connection.prepareStatement("SELECT id, data FROM entries WHERE type = ?");
		writeStatement = connection.prepareStatement("INSERT OR REPLACE INTO entries (type, id, data) VALUES (?, ?, ?)");
		deleteStatement = connection.prepareStatement("DELETE FROM entries WHERE type = ? AND id = ?");

		checkSchemaVersion();
		migrate(types);
		// Only a database this jar may write; outside a transaction, as VACUUM needs
		connection.commit();
		connection.setAutoCommit(true);
		compactIfMostlyFree();
		connection.setAutoCommit(false);
	}

	/**
	 * Gives the space of deleted rows back to the disk when most of the file is free, as after paths were first saved as
	 * shared pieces (optimisation round 5, step 2); SQLite otherwise keeps the file as large as it ever was.
	 */
	private void compactIfMostlyFree() {
		try (final Statement statement = connection.createStatement()) {
			final long pageSize = readPragma(statement, "page_size");
			final long pages = readPragma(statement, "page_count");
			final long free = readPragma(statement, "freelist_count");
			if (free * pageSize >= COMPACT_MIN_FREE_BYTES && free * 2 > pages) {
				final long startMillis = System.currentTimeMillis();
				statement.execute("VACUUM");
				System.out.println("Minecraft Transit Railway compacted " + databasePath + " from " + pages * pageSize / 1000000 + " MB to " + readPragma(statement, "page_count") * pageSize / 1000000 + " MB in " + (System.currentTimeMillis() - startMillis) + " ms");
			}
		} catch (Exception e) {
			System.out.println("Minecraft Transit Railway could not compact " + databasePath + "; it is used as it is");
			e.printStackTrace();
		}
	}

	private static long readPragma(Statement statement, String name) throws SQLException {
		try (final ResultSet resultSet = statement.executeQuery("PRAGMA " + name)) {
			return resultSet.next() ? resultSet.getLong(1) : 0;
		}
	}

	/**
	 * Refuses a database written by a newer jar, and marks one this jar writes.
	 */
	private void checkSchemaVersion() throws Exception {
		try (final PreparedStatement statement = connection.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
			statement.setString(1, KEY_SCHEMA_VERSION);
			try (final ResultSet resultSet = statement.executeQuery()) {
				if (resultSet.next()) {
					final int version = Integer.parseInt(resultSet.getString(1));
					if (version > SCHEMA_VERSION) {
						connection.rollback();
						connection.close();
						throw new IllegalStateException("Minecraft Transit Railway data at " + databasePath + " was written by a newer version (storage version " + version + ", this jar has " + SCHEMA_VERSION + "); it is left untouched");
					}
					connection.rollback();
					return;
				}
			}
		}
		try (final PreparedStatement statement = connection.prepareStatement("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)")) {
			statement.setString(1, KEY_SCHEMA_VERSION);
			statement.setString(2, String.valueOf(SCHEMA_VERSION));
			statement.executeUpdate();
		}
		connection.commit();
	}

	@Override
	public void forEach(String type, BiConsumer<Long, byte[]> consumer) throws Exception {
		selectStatement.setString(1, type);
		try (final ResultSet resultSet = selectStatement.executeQuery()) {
			while (resultSet.next()) {
				consumer.accept(resultSet.getLong(1), resultSet.getBytes(2));
			}
		}
	}

	@Override
	public void write(String type, long id, byte[] data) throws Exception {
		writeStatement.setString(1, type);
		writeStatement.setLong(2, id);
		writeStatement.setBytes(3, data);
		writeStatement.executeUpdate();
		hasPendingChanges = true;
	}

	@Override
	public void delete(String type, long id) throws Exception {
		deleteStatement.setString(1, type);
		deleteStatement.setLong(2, id);
		deleteStatement.executeUpdate();
		hasPendingChanges = true;
	}

	@Override
	public void publishTrackSections(long version, Map<Long, byte[]> added, Collection<Long> removed, Map<Long, byte[]> overlayPut, Collection<Long> overlayRemoved, long fingerprint, int count) throws Exception {
		// A row removed before and not yet swept comes back as part of this version
		try (final PreparedStatement statement = connection.prepareStatement("INSERT INTO track_sections (id, data, added_in, removed_in) VALUES (?, ?, ?, NULL) ON CONFLICT(id) DO UPDATE SET data = excluded.data, added_in = excluded.added_in, removed_in = NULL")) {
			for (final Map.Entry<Long, byte[]> entry : added.entrySet()) {
				statement.setLong(1, entry.getKey());
				statement.setBytes(2, entry.getValue());
				statement.setLong(3, version);
				statement.addBatch();
			}
			statement.executeBatch();
		}
		try (final PreparedStatement statement = connection.prepareStatement("UPDATE track_sections SET removed_in = ? WHERE id = ? AND removed_in IS NULL")) {
			for (final long id : removed) {
				statement.setLong(1, version);
				statement.setLong(2, id);
				statement.addBatch();
			}
			statement.executeBatch();
		}
		try (final PreparedStatement statement = connection.prepareStatement("INSERT OR REPLACE INTO signal_overlay (id, data) VALUES (?, ?)")) {
			for (final Map.Entry<Long, byte[]> entry : overlayPut.entrySet()) {
				statement.setLong(1, entry.getKey());
				statement.setBytes(2, entry.getValue());
				statement.addBatch();
			}
			statement.executeBatch();
		}
		try (final PreparedStatement statement = connection.prepareStatement("DELETE FROM signal_overlay WHERE id = ?")) {
			for (final long id : overlayRemoved) {
				statement.setLong(1, id);
				statement.addBatch();
			}
			statement.executeBatch();
		}
		// The root record last: until it is written, the version before is the one read
		try (final PreparedStatement statement = connection.prepareStatement("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)")) {
			statement.setString(1, KEY_TRACK_VERSION);
			statement.setString(2, String.valueOf(version));
			statement.addBatch();
			statement.setString(1, KEY_TRACK_FINGERPRINT);
			statement.setString(2, String.valueOf(fingerprint));
			statement.addBatch();
			statement.setString(1, KEY_TRACK_COUNT);
			statement.setString(2, String.valueOf(count));
			statement.addBatch();
			statement.executeBatch();
		}
		hasPendingChanges = true;
	}

	@Override
	public void writeLegLayer(Map<Long, byte[]> legsPut, Collection<Long> legsRemoved, Map<Long, byte[]> ownersPut, Collection<Long> ownersRemoved, Map<Long, byte[]> ownerSetsPut) throws Exception {
		writeRows("track_legs", legsPut, legsRemoved);
		writeRows("track_ownership", ownersPut, ownersRemoved);
		writeRows("track_owner_sets", ownerSetsPut, Collections.emptyList());
		hasPendingChanges = true;
	}

	private void writeRows(String table, Map<Long, byte[]> put, Collection<Long> removed) throws Exception {
		try (final PreparedStatement statement = connection.prepareStatement("INSERT OR REPLACE INTO " + table + " (id, data) VALUES (?, ?)")) {
			for (final Map.Entry<Long, byte[]> entry : put.entrySet()) {
				statement.setLong(1, entry.getKey());
				statement.setBytes(2, entry.getValue());
				statement.addBatch();
			}
			statement.executeBatch();
		}
		try (final PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
			for (final long id : removed) {
				statement.setLong(1, id);
				statement.addBatch();
			}
			statement.executeBatch();
		}
	}

	@Override
	public boolean storesPathPieces() {
		return true;
	}

	@Override
	public void loadPathPieces(BiConsumer<Long, byte[]> pieces, BiConsumer<Long, Long> refs) throws Exception {
		try (final Statement statement = connection.createStatement(); final ResultSet resultSet = statement.executeQuery("SELECT id, data FROM path_pieces")) {
			while (resultSet.next()) {
				pieces.accept(resultSet.getLong(1), resultSet.getBytes(2));
			}
		}
		try (final Statement statement = connection.createStatement(); final ResultSet resultSet = statement.executeQuery("SELECT owner, piece FROM path_piece_refs")) {
			while (resultSet.next()) {
				refs.accept(resultSet.getLong(1), resultSet.getLong(2));
			}
		}
	}

	@Override
	public void writePathPieces(Map<Long, byte[]> pieces) throws Exception {
		if (pieces.isEmpty()) {
			return;
		}
		try (final PreparedStatement statement = connection.prepareStatement("INSERT OR IGNORE INTO path_pieces (id, data) VALUES (?, ?)")) {
			for (final Map.Entry<Long, byte[]> entry : pieces.entrySet()) {
				statement.setLong(1, entry.getKey());
				statement.setBytes(2, entry.getValue());
				statement.addBatch();
			}
			statement.executeBatch();
		}
		hasPendingChanges = true;
	}

	@Override
	public void writePathPieceRefs(long owner, Collection<Long> pieces) throws Exception {
		try (final PreparedStatement statement = connection.prepareStatement("DELETE FROM path_piece_refs WHERE owner = ?")) {
			statement.setLong(1, owner);
			statement.executeUpdate();
		}
		try (final PreparedStatement statement = connection.prepareStatement("INSERT INTO path_piece_refs (owner, piece) VALUES (?, ?)")) {
			for (final long piece : pieces) {
				statement.setLong(1, owner);
				statement.setLong(2, piece);
				statement.addBatch();
			}
			statement.executeBatch();
		}
		hasPendingChanges = true;
	}

	@Override
	public void deletePathPieces(Collection<Long> pieces) throws Exception {
		if (pieces.isEmpty()) {
			return;
		}
		// Only pieces no one refers to, whatever the caller worked out
		try (final PreparedStatement statement = connection.prepareStatement("DELETE FROM path_pieces WHERE id = ? AND NOT EXISTS (SELECT 1 FROM path_piece_refs WHERE piece = ?)")) {
			for (final long piece : pieces) {
				statement.setLong(1, piece);
				statement.setLong(2, piece);
				statement.addBatch();
			}
			statement.executeBatch();
		}
		hasPendingChanges = true;
	}

	@Override
	public boolean storesDutyLayers() {
		return true;
	}

	@Override
	public void loadDutyFingerprints(BiConsumer<Long, Long> fingerprints) throws Exception {
		try (final Statement statement = connection.createStatement(); final ResultSet resultSet = statement.executeQuery("SELECT depot, fingerprint FROM duty_depots")) {
			while (resultSet.next()) {
				fingerprints.accept(resultSet.getLong(1), resultSet.getLong(2));
			}
		}
	}

	@Override
	public void writeDutyLayers(long depotId, long fingerprint, transitsim.plan.DutyLayers user, transitsim.plan.DutyLayers auto) throws Exception {
		deleteDutyLayers(depotId);
		final transitsim.plan.DutyLayers[] kinds = {user, auto};
		try (final PreparedStatement bodies = connection.prepareStatement("INSERT OR IGNORE INTO duty_bodies (id, data) VALUES (?, ?)");
			 final PreparedStatement services = connection.prepareStatement("INSERT OR IGNORE INTO duty_body_services (route, service, body) VALUES (?, ?, ?)");
			 final PreparedStatement groups = connection.prepareStatement("INSERT OR IGNORE INTO duty_groups (id, pool, data) VALUES (?, ?, ?)");
			 final PreparedStatement groupBodies = connection.prepareStatement("INSERT OR IGNORE INTO duty_group_bodies (grp, position, body) VALUES (?, ?, ?)");
			 final PreparedStatement assignments = connection.prepareStatement("INSERT OR REPLACE INTO duty_assignments (depot, kind, siding, slot, weekdays, duty, grp, body, data) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
			for (int kind = 0; kind < kinds.length; kind++) {
				final transitsim.plan.DutyLayers layers = kinds[kind];
				if (layers == null) {
					continue;
				}
				for (final transitsim.plan.DutyLayers.Body body : layers.bodies.values()) {
					bodies.setLong(1, body.key);
					bodies.setBytes(2, body.toBytes());
					bodies.addBatch();
					for (final transitsim.plan.DepotDuties.Segment segment : body.segments) {
						services.setLong(1, segment.routeId);
						services.setInt(2, segment.service);
						services.setLong(3, body.key);
						services.addBatch();
					}
				}
				for (final transitsim.plan.DutyLayers.Group group : layers.groups.values()) {
					groups.setLong(1, group.key);
					groups.setString(2, group.pool);
					groups.setBytes(3, group.toBytes());
					groups.addBatch();
					for (int i = 0; i < group.bodies.size(); i++) {
						groupBodies.setLong(1, group.key);
						groupBodies.setInt(2, i);
						groupBodies.setLong(3, group.bodies.get(i));
						groupBodies.addBatch();
					}
				}
				for (final transitsim.plan.DutyLayers.Assignment assignment : layers.assignments) {
					assignments.setLong(1, depotId);
					assignments.setInt(2, kind);
					assignments.setLong(3, assignment.sidingId);
					assignments.setInt(4, assignment.slotIndex);
					assignments.setInt(5, assignment.weekdays);
					assignments.setLong(6, assignment.dutyId);
					assignments.setLong(7, assignment.isEmptySlot() ? 0 : assignment.groupKey);
					assignments.setLong(8, assignment.getBodyKey(layers));
					assignments.setBytes(9, assignment.toBytes());
					assignments.addBatch();
				}
			}
			bodies.executeBatch();
			services.executeBatch();
			groups.executeBatch();
			groupBodies.executeBatch();
			assignments.executeBatch();
		}
		try (final PreparedStatement statement = connection.prepareStatement("INSERT OR REPLACE INTO duty_depots (depot, fingerprint) VALUES (?, ?)")) {
			statement.setLong(1, depotId);
			statement.setLong(2, fingerprint);
			statement.executeUpdate();
		}
		hasPendingChanges = true;
	}

	@Override
	public void deleteDutyLayers(long depotId) throws Exception {
		for (final String sql : new String[]{"DELETE FROM duty_assignments WHERE depot = ?", "DELETE FROM duty_depots WHERE depot = ?"}) {
			try (final PreparedStatement statement = connection.prepareStatement(sql)) {
				statement.setLong(1, depotId);
				statement.executeUpdate();
			}
		}
		hasPendingChanges = true;
	}

	@Override
	public void sweepDutyLayers() throws Exception {
		try (final Statement statement = connection.createStatement()) {
			statement.executeUpdate("DELETE FROM duty_groups WHERE NOT EXISTS (SELECT 1 FROM duty_assignments WHERE grp = duty_groups.id)");
			statement.executeUpdate("DELETE FROM duty_group_bodies WHERE NOT EXISTS (SELECT 1 FROM duty_groups WHERE id = duty_group_bodies.grp)");
			statement.executeUpdate("DELETE FROM duty_bodies WHERE NOT EXISTS (SELECT 1 FROM duty_group_bodies WHERE body = duty_bodies.id)");
			statement.executeUpdate("DELETE FROM duty_body_services WHERE NOT EXISTS (SELECT 1 FROM duty_bodies WHERE id = duty_body_services.body)");
		}
		hasPendingChanges = true;
	}

	@Override
	public List<long[]> findDutiesRunning(long routeId, int service) throws Exception {
		final List<long[]> duties = new ArrayList<>();
		try (final PreparedStatement statement = connection.prepareStatement("SELECT a.depot, a.kind, a.siding, a.slot, a.weekdays, a.duty FROM duty_body_services s JOIN duty_assignments a ON a.body = s.body WHERE s.route = ? AND s.service = ? ORDER BY a.depot, a.kind, a.siding, a.slot, a.weekdays")) {
			statement.setLong(1, routeId);
			statement.setInt(2, service);
			try (final ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					duties.add(new long[]{resultSet.getLong(1), resultSet.getLong(2), resultSet.getLong(3), resultSet.getLong(4), resultSet.getLong(5), resultSet.getLong(6)});
				}
			}
		}
		return duties;
	}

	@Override
	public List<Long> findDepotsUsingGroup(long groupKey) throws Exception {
		final List<Long> depots = new ArrayList<>();
		try (final PreparedStatement statement = connection.prepareStatement("SELECT DISTINCT depot FROM duty_assignments WHERE grp = ? ORDER BY depot")) {
			statement.setLong(1, groupKey);
			try (final ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					depots.add(resultSet.getLong(1));
				}
			}
		}
		return depots;
	}

	@Override
	public void loadLegLayer(BiConsumer<Long, byte[]> legs, BiConsumer<Long, byte[]> owners, BiConsumer<Long, byte[]> ownerSets) throws Exception {
		final String[] tables = {"track_legs", "track_ownership", "track_owner_sets"};
		final List<BiConsumer<Long, byte[]>> consumers = java.util.Arrays.asList(legs, owners, ownerSets);
		for (int i = 0; i < tables.length; i++) {
			try (final Statement statement = connection.createStatement(); final ResultSet resultSet = statement.executeQuery("SELECT id, data FROM " + tables[i])) {
				while (resultSet.next()) {
					consumers.get(i).accept(resultSet.getLong(1), resultSet.getBytes(2));
				}
			}
		}
	}

	@Override
	public void sweepTrackSections(long version) throws Exception {
		try (final PreparedStatement statement = connection.prepareStatement("DELETE FROM track_sections WHERE removed_in IS NOT NULL AND removed_in <= ?")) {
			statement.setLong(1, version);
			if (statement.executeUpdate() > 0) {
				hasPendingChanges = true;
			}
		}
	}

	@Override
	public long[] loadTrackSections(BiConsumer<Long, byte[]> sections, BiConsumer<Long, byte[]> overlay) throws Exception {
		final String version = readMeta(KEY_TRACK_VERSION);
		if (version == null) {
			return null;
		}
		final long publishedVersion = Long.parseLong(version);
		// What a version never published wrote is undone
		try (final Statement statement = connection.createStatement()) {
			final int dropped = statement.executeUpdate("DELETE FROM track_sections WHERE added_in > " + publishedVersion);
			final int kept = statement.executeUpdate("UPDATE track_sections SET removed_in = NULL WHERE removed_in > " + publishedVersion);
			if (dropped > 0 || kept > 0) {
				System.out.println("Minecraft Transit Railway: a version of the track sections was only partly written; " + dropped + " rows of it dropped and " + kept + " kept again");
				connection.commit();
			}
		}
		try (final PreparedStatement statement = connection.prepareStatement("SELECT id, data FROM track_sections WHERE removed_in IS NULL OR removed_in > ?")) {
			statement.setLong(1, publishedVersion);
			try (final ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					sections.accept(resultSet.getLong(1), resultSet.getBytes(2));
				}
			}
		}
		try (final Statement statement = connection.createStatement(); final ResultSet resultSet = statement.executeQuery("SELECT id, data FROM signal_overlay")) {
			while (resultSet.next()) {
				overlay.accept(resultSet.getLong(1), resultSet.getBytes(2));
			}
		}
		final String fingerprint = readMeta(KEY_TRACK_FINGERPRINT);
		final String count = readMeta(KEY_TRACK_COUNT);
		return new long[]{publishedVersion, fingerprint == null ? 0 : Long.parseLong(fingerprint), count == null ? -1 : Long.parseLong(count)};
	}

	private String readMeta(String key) throws Exception {
		try (final PreparedStatement statement = connection.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
			statement.setString(1, key);
			try (final ResultSet resultSet = statement.executeQuery()) {
				return resultSet.next() ? resultSet.getString(1) : null;
			}
		}
	}

	@Override
	public void writeGraphPath(String owner, long fingerprint, byte[] data) throws Exception {
		try (final PreparedStatement statement = connection.prepareStatement("INSERT OR REPLACE INTO graph_paths (owner, graph_fingerprint, data) VALUES (?, ?, ?)")) {
			statement.setString(1, owner);
			statement.setLong(2, fingerprint);
			statement.setBytes(3, data);
			statement.executeUpdate();
		}
		hasPendingChanges = true;
	}

	@Override
	public void writeSimulationData(byte[] data) throws Exception {
		try (final PreparedStatement statement = connection.prepareStatement("INSERT OR REPLACE INTO sim_data (key, data) VALUES ('world', ?)")) {
			statement.setBytes(1, data);
			statement.executeUpdate();
		}
		hasPendingChanges = true;
	}

	@Override
	public void commit() throws Exception {
		if (hasPendingChanges) {
			connection.commit();
			hasPendingChanges = false;
		}
	}

	@Override
	public void writeEditRevision(String key, int revision, String editor, boolean deleted) throws Exception {
		try (final PreparedStatement statement = connection.prepareStatement("INSERT OR REPLACE INTO edit_revisions (key, revision, editor, deleted) VALUES (?, ?, ?, ?)")) {
			statement.setString(1, key);
			statement.setInt(2, revision);
			statement.setString(3, editor);
			statement.setInt(4, deleted ? 1 : 0);
			statement.executeUpdate();
		}
		hasPendingChanges = true;
	}

	@Override
	public void forEachEditRevision(EditRevisionConsumer consumer) throws Exception {
		try (final PreparedStatement statement = connection.prepareStatement("SELECT key, revision, editor, deleted FROM edit_revisions"); final ResultSet resultSet = statement.executeQuery()) {
			while (resultSet.next()) {
				consumer.accept(resultSet.getString(1), resultSet.getInt(2), resultSet.getString(3), resultSet.getInt(4) != 0);
			}
		}
	}

	@Override
	public void rollback() throws Exception {
		connection.rollback();
		hasPendingChanges = false;
	}

	@Override
	public void backup() {
		try {
			if (!closed && isBackupDue() && backupRunning.compareAndSet(false, true)) {
				backupExecutor.execute(() -> {
					try {
						createBackup();
					} catch (Exception e) {
						e.printStackTrace();
					} finally {
						backupRunning.set(false);
					}
				});
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void close() throws Exception {
		if (closed) {
			return;
		}

		closed = true;
		commit();
		backupExecutor.shutdown();
		backupExecutor.awaitTermination(BACKUP_WAIT_SECONDS, TimeUnit.SECONDS);
		selectStatement.close();
		writeStatement.close();
		deleteStatement.close();
		connection.rollback();
		connection.setAutoCommit(true);
		try (final Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
		}
		connection.close();
	}

	private void migrate(String[] types) throws Exception {
		try (final PreparedStatement statement = connection.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
			statement.setString(1, KEY_MIGRATED);
			try (final ResultSet resultSet = statement.executeQuery()) {
				if (resultSet.next()) {
					connection.rollback();
					return;
				}
			}
		}

		final Path legacyPath = savePath.resolve(LEGACY_FOLDER);
		final RailwayDataStore[] sources = {new RailwayDataFileStore(legacyPath), new RailwayDataFileStore(savePath)};
		final int[] count = {0};
		for (final RailwayDataStore source : sources) {
			for (final String type : types) {
				source.forEach(type, (id, data) -> {
					try {
						write(type, id, data);
						count[0]++;
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
				});
			}
		}

		try (final PreparedStatement statement = connection.prepareStatement("INSERT INTO meta (key, value) VALUES (?, ?)")) {
			statement.setString(1, KEY_MIGRATED);
			statement.setString(2, String.valueOf(System.currentTimeMillis()));
			statement.executeUpdate();
		}
		connection.commit();
		hasPendingChanges = false;

		// The old files are kept in case the data has to be recovered
		for (final String type : types) {
			final Path typePath = savePath.resolve(type);
			if (Files.isDirectory(typePath)) {
				try {
					Files.createDirectories(legacyPath);
					Files.move(typePath, legacyPath.resolve(type));
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}

		if (count[0] > 0) {
			System.out.println("Migrated " + count[0] + " Minecraft Transit Railway data entries to " + databasePath);
		}
	}

	private boolean isBackupDue() throws Exception {
		final List<Path> backups = getBackups();
		return backups.isEmpty() || System.currentTimeMillis() - Files.getLastModifiedTime(backups.get(0)).toMillis() >= BACKUP_INTERVAL_MILLIS;
	}

	private void createBackup() throws Exception {
		final Path backup = RailwayDataBackups.createBackup(databasePath, backupsPath, RailwayDataBackups.now());
		if (backup == null) {
			System.out.println("Minecraft Transit Railway backup of " + databasePath + " failed its check and was dropped");
		}
	}

	private List<Path> getBackups() throws Exception {
		return RailwayDataBackups.getBackups(backupsPath);
	}

	/**
	 * An admin asks for a backup to be restored by putting its file name in {@code backups/restore.txt} while the server
	 * is stopped; on starting, the database as it is is kept aside as {@code mtr.db-before-restore-<time>}, the backup is
	 * made whole again in its place, and the request is renamed {@code restore-done.txt}.
	 */
	private void restoreRequested() throws Exception {
		final Path request = backupsPath.resolve(RESTORE_REQUEST);
		if (!Files.exists(request)) {
			return;
		}
		final String name = new String(Files.readAllBytes(request), java.nio.charset.StandardCharsets.UTF_8).trim();
		final Path backup = backupsPath.resolve(name);
		if (name.isEmpty() || name.contains("/") || name.contains("\\") || !Files.exists(backup)) {
			System.out.println("Minecraft Transit Railway: the backup asked for in " + request + " isn't there: " + name);
			return;
		}
		final Path restored = savePath.resolve(RailwayDataFileStore.DATABASE_NAME + ".restored");
		if (!RailwayDataBackups.restore(backup, restored)) {
			System.out.println("Minecraft Transit Railway: the backup " + backup + " couldn't be restored, as it or its base is damaged or missing; nothing was changed");
			return;
		}
		final String suffix = "-before-restore-" + System.currentTimeMillis();
		for (final String databaseSuffix : RailwayDataFileStore.DATABASE_SUFFIXES) {
			final Path path = savePath.resolve(RailwayDataFileStore.DATABASE_NAME + databaseSuffix);
			if (Files.exists(path)) {
				Files.move(path, savePath.resolve(RailwayDataFileStore.DATABASE_NAME + databaseSuffix + suffix));
			}
		}
		Files.move(restored, databasePath);
		Files.move(request, backupsPath.resolve("restore-done.txt"), StandardCopyOption.REPLACE_EXISTING);
		System.out.println("Minecraft Transit Railway data restored from " + backup + " as asked; the data as it was is kept as " + RailwayDataFileStore.DATABASE_NAME + suffix);
	}

	private void quarantineDatabase() throws Exception {
		final String suffix = "-corrupted-" + System.currentTimeMillis();
		for (final String databaseSuffix : RailwayDataFileStore.DATABASE_SUFFIXES) {
			final Path path = savePath.resolve(RailwayDataFileStore.DATABASE_NAME + databaseSuffix);
			if (Files.exists(path)) {
				Files.move(path, savePath.resolve(RailwayDataFileStore.DATABASE_NAME + databaseSuffix + suffix));
			}
		}
	}

	private void restoreLatestBackup() throws Exception {
		for (final Path backup : getBackups()) {
			if (RailwayDataBackups.restore(backup, databasePath)) {
				System.out.println("Restored Minecraft Transit Railway data from " + backup);
				return;
			}
		}
		System.out.println("No Minecraft Transit Railway backup available, trying the old data files");
	}

	private static Connection connect(Path path) throws SQLException {
		return new JDBC().connect(JDBC.PREFIX + path, new Properties());
	}

	private static boolean isHealthy(Path path) throws SQLException {
		try (final Connection connection = connect(path); final Statement statement = connection.createStatement(); final ResultSet resultSet = statement.executeQuery("PRAGMA quick_check")) {
			return resultSet.next() && "ok".equalsIgnoreCase(resultSet.getString(1));
		} catch (SQLiteException e) {
			final SQLiteErrorCode errorCode = e.getResultCode();
			if (errorCode == SQLiteErrorCode.SQLITE_CORRUPT || errorCode == SQLiteErrorCode.SQLITE_NOTADB) {
				return false;
			}
			throw e;
		}
	}
}
