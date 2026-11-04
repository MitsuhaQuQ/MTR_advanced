package mtr.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.JDBC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backups in batches, each a base and the differences from it.
 */
class RailwayDataBackupsTest {

	@TempDir
	Path folder;

	@Test
	void aBackupOfDifferencesIsMadeWholeAgain() throws Exception {
		final Path database = folder.resolve("mtr.db");
		final Path backups = Files.createDirectories(folder.resolve("backups"));
		execute(database, "CREATE TABLE entries (type TEXT NOT NULL, id INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY (type, id)) WITHOUT ROWID",
				"CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
				"INSERT INTO entries VALUES ('routes', 1, x'01'), ('routes', 2, x'02'), ('rails', 5, x'05')",
				"INSERT INTO meta VALUES ('migrated', '1')");
		final Path base = RailwayDataBackups.createBackup(database, backups, "20261104-100000");
		assertTrue(base.getFileName().toString().endsWith("-base.db"), base.toString());

		execute(database, "UPDATE entries SET data = x'22' WHERE type = 'routes' AND id = 2",
				"DELETE FROM entries WHERE type = 'rails'",
				"INSERT INTO entries VALUES ('stations', 9, x'09')",
				// A table the base doesn't have
				"CREATE TABLE edit_revisions (key TEXT PRIMARY KEY, revision INTEGER NOT NULL, editor TEXT NOT NULL, deleted INTEGER NOT NULL)",
				"INSERT INTO edit_revisions VALUES ('route:1:name', 3, 'Alice', 0)");
		final Path increment = RailwayDataBackups.createBackup(database, backups, "20261104-110000");
		assertTrue(increment.getFileName().toString().contains("-inc-20261104-100000"), increment.toString());
		// Only what changed: the changed and the new route rows, nothing of the unchanged one
		assertEquals(2, count(increment, "SELECT COUNT(*) FROM entries"));
		assertTrue(Files.size(increment) > 0);

		final Path restored = folder.resolve("restored.db");
		assertTrue(RailwayDataBackups.restore(increment, restored));
		assertEquals(rows(database, "SELECT type, id, hex(data) FROM entries ORDER BY type, id"), rows(restored, "SELECT type, id, hex(data) FROM entries ORDER BY type, id"));
		assertEquals(rows(database, "SELECT key, value FROM meta"), rows(restored, "SELECT key, value FROM meta"));
		assertEquals(rows(database, "SELECT key, revision, editor FROM edit_revisions"), rows(restored, "SELECT key, revision, editor FROM edit_revisions"));

		// The base on its own is the data as it was then
		final Path restoredBase = folder.resolve("restored-base.db");
		assertTrue(RailwayDataBackups.restore(base, restoredBase));
		assertEquals(3, count(restoredBase, "SELECT COUNT(*) FROM entries"));
	}

	@Test
	void batchesStartAnewAndOnlyTheNewestAreKept() throws Exception {
		final Path database = folder.resolve("mtr.db");
		final Path backups = Files.createDirectories(folder.resolve("backups"));
		execute(database, "CREATE TABLE entries (type TEXT NOT NULL, id INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY (type, id)) WITHOUT ROWID");
		final int total = RailwayDataBackups.BATCH_SIZE * (RailwayDataBackups.BATCHES_TO_KEEP + 1) + 2;
		for (int i = 0; i < total; i++) {
			execute(database, "INSERT INTO entries VALUES ('routes', " + i + ", x'01')");
			assertNotNull(RailwayDataBackups.createBackup(database, backups, String.format("20261104-%06d", i)));
		}
		final List<List<Path>> batches = RailwayDataBackups.getBatches(backups);
		assertEquals(RailwayDataBackups.BATCHES_TO_KEEP, batches.size());
		// The newest batch has just started: its base and one more
		assertEquals(2, batches.get(0).size());
		assertTrue(batches.get(0).get(1).getFileName().toString().endsWith("-base.db"));
		// Each backup kept is made whole again with every row it had
		final Path newest = batches.get(0).get(0);
		final Path restored = folder.resolve("restored.db");
		assertTrue(RailwayDataBackups.restore(newest, restored));
		assertEquals(total, count(restored, "SELECT COUNT(*) FROM entries"));
		// A backup whose base is gone can't be
		Files.delete(batches.get(0).get(1));
		assertFalse(RailwayDataBackups.restore(newest, folder.resolve("orphan.db")));
	}

	private static void execute(Path path, String... sqls) throws Exception {
		try (final Connection connection = new JDBC().connect(JDBC.PREFIX + path, new Properties()); final Statement statement = connection.createStatement()) {
			for (final String sql : sqls) {
				statement.execute(sql);
			}
		}
	}

	private static int count(Path path, String sql) throws Exception {
		try (final Connection connection = new JDBC().connect(JDBC.PREFIX + path, new Properties()); final Statement statement = connection.createStatement(); final ResultSet resultSet = statement.executeQuery(sql)) {
			return resultSet.next() ? resultSet.getInt(1) : -1;
		}
	}

	private static List<String> rows(Path path, String sql) throws Exception {
		final List<String> rows = new ArrayList<>();
		try (final Connection connection = new JDBC().connect(JDBC.PREFIX + path, new Properties()); final Statement statement = connection.createStatement(); final ResultSet resultSet = statement.executeQuery(sql)) {
			final int columns = resultSet.getMetaData().getColumnCount();
			while (resultSet.next()) {
				final StringBuilder row = new StringBuilder();
				for (int i = 1; i <= columns; i++) {
					row.append(resultSet.getString(i)).append('|');
				}
				rows.add(row.toString());
			}
		}
		return rows;
	}
}
