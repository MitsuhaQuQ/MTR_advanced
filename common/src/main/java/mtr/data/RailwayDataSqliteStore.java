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
	private static final String KEY_GRAPH_FINGERPRINT = "graph_fingerprint";
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
			statement.execute("CREATE TABLE IF NOT EXISTS graph_paths (owner TEXT PRIMARY KEY, graph_fingerprint INTEGER NOT NULL, data BLOB NOT NULL)");
			statement.execute("CREATE TABLE IF NOT EXISTS sim_data (key TEXT PRIMARY KEY, data BLOB NOT NULL)");
			// The revision of each part players edit, kept so edits are checked the same after the server is started again
			statement.execute("CREATE TABLE IF NOT EXISTS edit_revisions (key TEXT PRIMARY KEY, revision INTEGER NOT NULL, editor TEXT NOT NULL, deleted INTEGER NOT NULL)");
		}
		connection.setAutoCommit(false);

		selectStatement = connection.prepareStatement("SELECT id, data FROM entries WHERE type = ?");
		writeStatement = connection.prepareStatement("INSERT OR REPLACE INTO entries (type, id, data) VALUES (?, ?, ?)");
		deleteStatement = connection.prepareStatement("DELETE FROM entries WHERE type = ? AND id = ?");

		checkSchemaVersion();
		migrate(types);
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
	public void replaceGraphSections(Map<Long, byte[]> sections, long fingerprint) throws Exception {
		try (final Statement statement = connection.createStatement()) {
			statement.execute("DELETE FROM graph_sections");
		}
		try (final PreparedStatement statement = connection.prepareStatement("INSERT INTO graph_sections (id, data) VALUES (?, ?)")) {
			for (final Map.Entry<Long, byte[]> entry : sections.entrySet()) {
				statement.setLong(1, entry.getKey());
				statement.setBytes(2, entry.getValue());
				statement.addBatch();
			}
			statement.executeBatch();
		}
		try (final PreparedStatement statement = connection.prepareStatement("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)")) {
			statement.setString(1, KEY_GRAPH_FINGERPRINT);
			statement.setString(2, String.valueOf(fingerprint));
			statement.executeUpdate();
		}
		hasPendingChanges = true;
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
	public void forEachGraphSection(BiConsumer<Long, byte[]> consumer) throws Exception {
		try (final Statement statement = connection.createStatement(); final ResultSet resultSet = statement.executeQuery("SELECT id, data FROM graph_sections")) {
			while (resultSet.next()) {
				consumer.accept(resultSet.getLong(1), resultSet.getBytes(2));
			}
		}
	}

	@Override
	public long getGraphFingerprint() throws Exception {
		try (final PreparedStatement statement = connection.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
			statement.setString(1, KEY_GRAPH_FINGERPRINT);
			try (final ResultSet resultSet = statement.executeQuery()) {
				return resultSet.next() ? Long.parseLong(resultSet.getString(1)) : 0;
			}
		}
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
