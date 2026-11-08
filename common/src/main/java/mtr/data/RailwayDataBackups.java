package mtr.data;

import org.sqlite.JDBC;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Backups of the railway database kept small: backups come in batches, each starting with a whole copy (the base),
 * followed by backups that only hold what differs from their batch's base, each table's rows that are new or changed
 * since and the keys of the rows taken away. A backup is made whole again from its base and its own differences.
 * <p>
 * Names: {@code mtr-<time>-base.db} for a base, {@code mtr-<time>-inc.db} for the others, whose base is the newest base
 * before them, and {@code mtr-<time>.db} for a whole copy made before batches, which counts as a base. Names are kept
 * short, as SQLite on Windows can't open a path longer than 260 characters and servers are often kept deep in folders;
 * {@code mtr-<time>-inc-<time of its base>.db}, as the first backups of differences were named, is still read.
 */
public final class RailwayDataBackups {

	/**
	 * Each table with its key columns, then its other columns.
	 */
	private static final String[][][] TABLES = {
			{{"entries"}, {"type", "id"}, {"data"}},
			{{"meta"}, {"key"}, {"value"}},
			{{"graph_sections"}, {"id"}, {"data"}},
			{{"track_sections"}, {"id"}, {"data", "added_in", "removed_in"}},
			{{"signal_overlay"}, {"id"}, {"data"}},
			{{"track_legs"}, {"id"}, {"data"}},
			{{"track_ownership"}, {"id"}, {"data"}},
			{{"track_owner_sets"}, {"id"}, {"data"}},
			{{"graph_paths"}, {"owner"}, {"graph_fingerprint", "data"}},
			{{"sim_data"}, {"key"}, {"data"}},
			{{"path_pieces"}, {"id"}, {"data"}},
			{{"path_piece_refs"}, {"owner", "piece"}, {}},
			{{"edit_revisions"}, {"key"}, {"revision", "editor", "deleted"}},
	};
	/**
	 * A base and the backups after it.
	 */
	public static final int BATCH_SIZE = 6;
	public static final int BATCHES_TO_KEEP = 4;
	private static final String PREFIX = "mtr-";
	private static final String EXTENSION = ".db";
	private static final String BASE = "-base";
	private static final String INCREMENT = "-inc";
	private static final String TEMPORARY_NAME = "next.tmp";
	private static final int TIME_LENGTH = "yyyyMMdd-HHmmss".length();
	private static final String DELETED_TABLE = "backup_deleted";
	/**
	 * In a backup of differences, the time of its base, so it is never made whole from another.
	 */
	private static final String BASE_TABLE = "backup_base";

	private RailwayDataBackups() {
	}

	/**
	 * Backs up the database: as a difference from the newest base while its batch isn't full, or else as a new base.
	 * Old batches past the ones kept are deleted.
	 *
	 * @return the backup made, or null if it failed its check
	 */
	public static Path createBackup(Path databasePath, Path backupsPath, String time) throws Exception {
		final List<List<Path>> batches = getBatches(backupsPath);
		final List<Path> newestBatch = batches.isEmpty() ? null : batches.get(0);
		final Path base = newestBatch == null ? null : newestBatch.get(newestBatch.size() - 1);
		final boolean asIncrement = base != null && newestBatch.size() < BATCH_SIZE && isHealthy(base);
		final String name = PREFIX + time + (asIncrement ? INCREMENT : BASE) + EXTENSION;
		final Path temporaryPath = backupsPath.resolve(TEMPORARY_NAME);
		Files.deleteIfExists(temporaryPath);

		try (final Connection connection = connect(databasePath); final Statement statement = connection.createStatement()) {
			if (asIncrement) {
				writeDifferences(statement, base, temporaryPath);
			} else {
				statement.execute("VACUUM INTO " + quote(temporaryPath.toString()));
			}
		}

		Path made = null;
		if (isHealthy(temporaryPath)) {
			made = backupsPath.resolve(name);
			Files.move(temporaryPath, made, StandardCopyOption.REPLACE_EXISTING);
		} else {
			Files.deleteIfExists(temporaryPath);
		}

		final List<List<Path>> newBatches = getBatches(backupsPath);
		for (int i = BATCHES_TO_KEEP; i < newBatches.size(); i++) {
			for (final Path path : newBatches.get(i)) {
				Files.deleteIfExists(path);
			}
		}
		return made;
	}

	/**
	 * Makes a backup whole again as a database at the target.
	 *
	 * @return false if it can't be, as its base is missing or damaged
	 */
	public static boolean restore(Path backup, Path target) throws Exception {
		final Path temporaryPath = target.resolveSibling(target.getFileName() + ".restoring");
		Files.deleteIfExists(temporaryPath);
		if (!isIncrement(backup)) {
			if (!isHealthy(backup)) {
				return false;
			}
			Files.copy(backup, temporaryPath, StandardCopyOption.REPLACE_EXISTING);
		} else {
			final Path base = findBase(backup);
			if (base == null || !isHealthy(base) || !isHealthy(backup)) {
				return false;
			}
			Files.copy(base, temporaryPath, StandardCopyOption.REPLACE_EXISTING);
			try (final Connection connection = connect(temporaryPath); final Statement statement = connection.createStatement()) {
				statement.execute("ATTACH DATABASE " + quote(backup.toString()) + " AS increment");
				connection.setAutoCommit(false);
				for (final String[][] table : TABLES) {
					final String name = table[0][0];
					if (!hasTable(statement, "increment", name)) {
						continue;
					}
					if (!hasTable(statement, "main", name)) {
						statement.execute("CREATE TABLE main." + name + " AS SELECT * FROM increment." + name + " WHERE 0");
					}
					final String keys = String.join(", ", table[1]);
					final String[] keyColumns = new String[table[1].length];
					for (int i = 0; i < keyColumns.length; i++) {
						keyColumns[i] = "k" + (i + 1);
					}
					statement.execute("DELETE FROM main." + name + " WHERE (" + keys + ") IN (SELECT " + String.join(", ", keyColumns) + " FROM increment." + DELETED_TABLE + " WHERE name = " + quote(name) + ")");
					final String columns = String.join(", ", allColumns(table));
					statement.execute("INSERT OR REPLACE INTO main." + name + " (" + columns + ") SELECT " + columns + " FROM increment." + name);
				}
				connection.commit();
				connection.setAutoCommit(true);
				statement.execute("DETACH DATABASE increment");
			}
		}
		if (!isHealthy(temporaryPath)) {
			Files.deleteIfExists(temporaryPath);
			return false;
		}
		Files.move(temporaryPath, target, StandardCopyOption.REPLACE_EXISTING);
		return true;
	}

	/**
	 * @return the backups, newest first
	 */
	public static List<Path> getBackups(Path backupsPath) throws Exception {
		try (final Stream<Path> stream = Files.list(backupsPath)) {
			final List<Path> backups = stream.filter(path -> {
				final String name = path.getFileName().toString();
				return name.startsWith(PREFIX) && name.endsWith(EXTENSION) && name.length() >= PREFIX.length() + TIME_LENGTH + EXTENSION.length();
			}).sorted(Comparator.comparing(RailwayDataBackups::getTime).thenComparing(path -> path.getFileName().toString())).collect(Collectors.toCollection(ArrayList::new));
			Collections.reverse(backups);
			return backups;
		}
	}

	/**
	 * @return the batches, newest first, each with its backups newest first and its base last; a backup whose base is
	 * gone makes no batch
	 */
	public static List<List<Path>> getBatches(Path backupsPath) throws Exception {
		final TreeMap<String, List<Path>> batches = new TreeMap<>();
		final List<Path> backups = getBackups(backupsPath);
		for (final Path backup : backups) {
			if (!isIncrement(backup)) {
				batches.put(getTime(backup), new ArrayList<>());
			}
		}
		for (final Path backup : backups) {
			final String baseTime = getBaseTime(backup, batches.navigableKeySet());
			final List<Path> batch = baseTime == null ? null : batches.get(baseTime);
			if (batch != null) {
				batch.add(backup);
			}
		}
		final List<List<Path>> newestFirst = new ArrayList<>(batches.descendingMap().values());
		newestFirst.removeIf(List::isEmpty);
		return newestFirst;
	}

	public static String now() {
		return new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
	}

	private static void writeDifferences(Statement statement, Path base, Path temporaryPath) throws SQLException {
		statement.execute("ATTACH DATABASE " + quote(base.toString()) + " AS base");
		statement.execute("ATTACH DATABASE " + quote(temporaryPath.toString()) + " AS increment");
		// No journal file beside it, which would need a longer path; a failed backup is dropped anyway
		statement.execute("PRAGMA increment.journal_mode=MEMORY");
		// One read of the database for all its tables, so they are as they were together
		statement.execute("BEGIN");
		try {
			statement.execute("CREATE TABLE increment." + BASE_TABLE + " (time TEXT NOT NULL)");
			statement.execute("INSERT INTO increment." + BASE_TABLE + " VALUES (" + quote(getTime(base)) + ")");
			statement.execute("CREATE TABLE increment." + DELETED_TABLE + " (name TEXT NOT NULL, k1, k2)");
			for (final String[][] table : TABLES) {
				final String name = table[0][0];
				if (!hasTable(statement, "main", name)) {
					continue;
				}
				final String columns = String.join(", ", allColumns(table));
				statement.execute("CREATE TABLE increment." + name + " AS SELECT " + columns + " FROM main." + name + " WHERE 0");
				if (!hasTable(statement, "base", name)) {
					statement.execute("INSERT INTO increment." + name + " SELECT " + columns + " FROM main." + name);
					continue;
				}
				final String join = joinOn(table[1]);
				final StringBuilder changed = new StringBuilder();
				for (final String column : table[2]) {
					changed.append(" OR b.").append(column).append(" IS NOT m.").append(column);
				}
				statement.execute("INSERT INTO increment." + name + " SELECT " + prefixed("m", allColumns(table)) + " FROM main." + name + " m LEFT JOIN base." + name + " b ON " + join + " WHERE b." + table[1][0] + " IS NULL" + changed);
				final String deletedKeys = prefixed("b", table[1]) + (table[1].length == 1 ? ", NULL" : "");
				statement.execute("INSERT INTO increment." + DELETED_TABLE + " SELECT " + quote(name) + ", " + deletedKeys + " FROM base." + name + " b LEFT JOIN main." + name + " m ON " + join + " WHERE m." + table[1][0] + " IS NULL");
			}
			statement.execute("COMMIT");
		} catch (SQLException e) {
			statement.execute("ROLLBACK");
			throw e;
		} finally {
			statement.execute("DETACH DATABASE increment");
			statement.execute("DETACH DATABASE base");
		}
	}

	private static Path findBase(Path backup) throws Exception {
		final List<Path> backups = getBackups(backup.getParent());
		final TreeSet<String> baseTimes = new TreeSet<>();
		backups.forEach(path -> {
			if (!isIncrement(path)) {
				baseTimes.add(getTime(path));
			}
		});
		final String recordedTime = getRecordedBaseTime(backup);
		final String baseTime = recordedTime == null ? getBaseTime(backup, baseTimes) : recordedTime;
		for (final Path path : backups) {
			if (!isIncrement(path) && getTime(path).equals(baseTime)) {
				return path;
			}
		}
		return null;
	}

	private static String getRecordedBaseTime(Path backup) throws SQLException {
		try (final Connection connection = connect(backup); final Statement statement = connection.createStatement()) {
			if (!hasTable(statement, "main", BASE_TABLE)) {
				return null;
			}
			try (final ResultSet resultSet = statement.executeQuery("SELECT time FROM " + BASE_TABLE)) {
				return resultSet.next() ? resultSet.getString(1) : null;
			}
		}
	}

	private static boolean isIncrement(Path backup) {
		return backup.getFileName().toString().contains(INCREMENT);
	}

	private static String getTime(Path backup) {
		return backup.getFileName().toString().substring(PREFIX.length(), PREFIX.length() + TIME_LENGTH);
	}

	/**
	 * @param baseTimes the times of the bases there are
	 * @return the time of the backup's base, or null if there is none
	 */
	private static String getBaseTime(Path backup, NavigableSet<String> baseTimes) {
		if (!isIncrement(backup)) {
			return getTime(backup);
		}
		final String name = backup.getFileName().toString();
		final int index = name.indexOf(INCREMENT + "-");
		if (index >= 0 && name.length() >= index + INCREMENT.length() + 1 + TIME_LENGTH) {
			final String named = name.substring(index + INCREMENT.length() + 1, index + INCREMENT.length() + 1 + TIME_LENGTH);
			return baseTimes.contains(named) ? named : null;
		}
		// Made against the newest base there was then
		return baseTimes.floor(getTime(backup));
	}

	private static List<String> allColumns(String[][] table) {
		final List<String> columns = new ArrayList<>(Arrays.asList(table[1]));
		columns.addAll(Arrays.asList(table[2]));
		return columns;
	}

	private static String prefixed(String alias, String[] columns) {
		return prefixed(alias, Arrays.asList(columns));
	}

	private static String prefixed(String alias, List<String> columns) {
		return columns.stream().map(column -> alias + "." + column).collect(Collectors.joining(", "));
	}

	private static String joinOn(String[] keys) {
		return Arrays.stream(keys).map(key -> "b." + key + " = m." + key).collect(Collectors.joining(" AND "));
	}

	private static boolean hasTable(Statement statement, String schema, String name) throws SQLException {
		try (final ResultSet resultSet = statement.executeQuery("SELECT 1 FROM " + schema + ".sqlite_master WHERE type = 'table' AND name = " + quote(name))) {
			return resultSet.next();
		}
	}

	private static String quote(String text) {
		return "'" + text.replace("'", "''") + "'";
	}

	private static Connection connect(Path path) throws SQLException {
		return new JDBC().connect(JDBC.PREFIX + path, new Properties());
	}

	public static boolean isHealthy(Path path) throws SQLException {
		if (!Files.exists(path)) {
			return false;
		}
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
