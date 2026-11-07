package mtr.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.JDBC;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The track section layer is saved by version (stages document round 1): a version is read only once its root record is
 * written, rows of a version never published are dropped on loading, and rows a version removed are deleted only after it.
 */
class TrackSectionStoreTest {

	@TempDir
	Path folder;

	private static Map<Long, byte[]> rows(long... ids) {
		final Map<Long, byte[]> rows = new TreeMap<>();
		for (final long id : ids) {
			rows.put(id, new byte[]{(byte) id});
		}
		return rows;
	}

	private Set<Long> load(long[] rootOut) throws Exception {
		final RailwayDataSqliteStore store = new RailwayDataSqliteStore(folder, new String[0]);
		try {
			final Set<Long> ids = new TreeSet<>();
			final long[] root = store.loadTrackSections((id, data) -> ids.add(id), (id, data) -> {
			});
			if (rootOut != null && root != null) {
				System.arraycopy(root, 0, rootOut, 0, 3);
			}
			return ids;
		} finally {
			store.close();
		}
	}

	private void execute(String... sql) throws Exception {
		try (final Connection connection = JDBC.createConnection(JDBC.PREFIX + folder.resolve("mtr.db"), new Properties()); final Statement statement = connection.createStatement()) {
			for (final String line : sql) {
				statement.execute(line);
			}
		}
	}

	@Test
	void onlyAPublishedVersionIsRead() throws Exception {
		RailwayDataSqliteStore store = new RailwayDataSqliteStore(folder, new String[0]);
		store.publishTrackSections(1, rows(1, 2), Collections.emptyList(), rows(1), Collections.emptyList(), 11, 2);
		store.commit();
		store.close();
		final long[] root = new long[3];
		assertEquals(new TreeSet<>(Arrays.asList(1L, 2L)), load(root));
		assertArrayEquals(new long[]{1, 11, 2}, root);

		// Version 2 written but its root record never: as when the rows went in a transaction of their own and the server
		// stopped before the root's
		store = new RailwayDataSqliteStore(folder, new String[0]);
		store.publishTrackSections(2, rows(3), Collections.singletonList(1L), rows(), Collections.emptyList(), 22, 2);
		store.commit();
		store.close();
		execute("UPDATE meta SET value = '1' WHERE key = 'track_version'");
		// Version 1 as it was: row 3 dropped, row 1 back
		assertEquals(new TreeSet<>(Arrays.asList(1L, 2L)), load(null));

		// Version 2 published, then swept: row 1 is gone for good
		store = new RailwayDataSqliteStore(folder, new String[0]);
		store.publishTrackSections(2, rows(3), Collections.singletonList(1L), rows(), Collections.emptyList(), 22, 2);
		store.commit();
		// Before sweeping, the removed row is still kept but not read
		store.close();
		assertEquals(new TreeSet<>(Arrays.asList(2L, 3L)), load(root));
		assertArrayEquals(new long[]{2, 22, 2}, root);
		store = new RailwayDataSqliteStore(folder, new String[0]);
		store.sweepTrackSections(2);
		store.commit();
		// A row removed and swept can come back in a later version
		store.publishTrackSections(3, rows(1), Collections.emptyList(), rows(), Collections.emptyList(), 33, 3);
		store.commit();
		store.close();
		assertEquals(new TreeSet<>(Arrays.asList(1L, 2L, 3L)), load(root));
		assertArrayEquals(new long[]{3, 33, 3}, root);
	}

	@Test
	void aRowRemovedAndAddedAgainBeforeSweepingIsKept() throws Exception {
		final RailwayDataSqliteStore store = new RailwayDataSqliteStore(folder, new String[0]);
		store.publishTrackSections(1, rows(1, 2), Collections.emptyList(), rows(), Collections.emptyList(), 0, 2);
		store.commit();
		store.publishTrackSections(2, rows(), Collections.singletonList(1L), rows(), Collections.emptyList(), 0, 1);
		store.commit();
		store.publishTrackSections(3, rows(1), Collections.emptyList(), rows(), Collections.emptyList(), 0, 2);
		store.commit();
		// Sweeping what version 2 removed doesn't take the row version 3 added back
		store.sweepTrackSections(2);
		store.commit();
		store.close();
		assertEquals(new TreeSet<>(Arrays.asList(1L, 2L)), load(null));
	}
}
