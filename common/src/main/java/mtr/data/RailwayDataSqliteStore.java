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
	private static final long BACKUP_INTERVAL_MILLIS = 60 * 60 * 1000;
	private static final int BACKUPS_TO_KEEP = 5;
	private static final int BACKUP_WAIT_SECONDS = 60;

	public RailwayDataSqliteStore(Path savePath, String[] types) throws Exception {
		this.savePath = savePath;
		databasePath = savePath.resolve(RailwayDataFileStore.DATABASE_NAME);
		backupsPath = savePath.resolve(BACKUPS_FOLDER);
		Files.createDirectories(backupsPath);

		if (Files.exists(databasePath) && !isHealthy(databasePath)) {
			System.out.println("Minecraft Transit Railway database is corrupted: " + databasePath);
			quarantineDatabase();
			restoreLatestBackup();
		}

		connection = connect(databasePath);
		try (final Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA journal_mode=WAL");
			statement.execute("PRAGMA synchronous=NORMAL");
			statement.execute("CREATE TABLE IF NOT EXISTS entries (type TEXT NOT NULL, id INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY (type, id)) WITHOUT ROWID");
			statement.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
		}
		connection.setAutoCommit(false);

		selectStatement = connection.prepareStatement("SELECT id, data FROM entries WHERE type = ?");
		writeStatement = connection.prepareStatement("INSERT OR REPLACE INTO entries (type, id, data) VALUES (?, ?, ?)");
		deleteStatement = connection.prepareStatement("DELETE FROM entries WHERE type = ? AND id = ?");

		migrate(types);
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
	public void commit() throws Exception {
		if (hasPendingChanges) {
			connection.commit();
			hasPendingChanges = false;
		}
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
		final String name = "mtr-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + BACKUP_EXTENSION;
		final Path temporaryPath = backupsPath.resolve(name + TEMPORARY_EXTENSION);
		Files.deleteIfExists(temporaryPath);

		try (final Connection backupConnection = connect(databasePath); final Statement statement = backupConnection.createStatement()) {
			statement.execute("VACUUM INTO '" + temporaryPath.toString().replace("'", "''") + "'");
		}

		if (isHealthy(temporaryPath)) {
			Files.move(temporaryPath, backupsPath.resolve(name), StandardCopyOption.REPLACE_EXISTING);
		} else {
			Files.deleteIfExists(temporaryPath);
		}

		final List<Path> backups = getBackups();
		for (int i = BACKUPS_TO_KEEP; i < backups.size(); i++) {
			Files.deleteIfExists(backups.get(i));
		}
	}

	private List<Path> getBackups() throws Exception {
		try (final Stream<Path> stream = Files.list(backupsPath)) {
			final List<Path> backups = stream.filter(path -> path.getFileName().toString().endsWith(BACKUP_EXTENSION) && !path.getFileName().toString().startsWith(".")).sorted().collect(Collectors.toCollection(ArrayList::new));
			Collections.reverse(backups);
			return backups;
		}
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
			if (isHealthy(backup)) {
				Files.copy(backup, databasePath);
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
