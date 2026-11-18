package mtr.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** How much each train type is used (0.101.0): counted over time, fading, ranked, kept in the database */
class TrainUsageTest {

	@TempDir
	Path folder;

	private static Map<String, Integer> counts(Object... pairs) {
		final Map<String, Integer> counts = new HashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			counts.put((String) pairs[i], (Integer) pairs[i + 1]);
		}
		return counts;
	}

	@Test
	void countedOverTimeAndRanked() {
		final TrainUsage usage = new TrainUsage();
		assertFalse(usage.update(counts("a", 3, "b", 1), 0), "the first count only starts the clock");
		assertTrue(usage.update(counts("a", 3, "b", 1), 20_000));
		assertTrue(usage.update(counts("a", 3, "b", 1, "c", 10), 40_000));
		final List<Map.Entry<String, Double>> ranking = usage.ranking();
		assertEquals(Arrays.asList("c", "a", "b"), Arrays.asList(ranking.get(0).getKey(), ranking.get(1).getKey(), ranking.get(2).getKey()));
		assertEquals(3 * 40, ranking.get(1).getValue(), 0.01, "3 trains for 40 s");
		assertEquals(10 * 20, ranking.get(0).getValue(), 0.01, "10 trains for 20 s");
	}

	@Test
	void rankedByTheTrainsNowFirst() {
		final TrainUsage usage = new TrainUsage();
		// A long past for "old", but more trains of "new" now: "new" first; "gone" has a past and no train now
		usage.restore("old", 100000);
		usage.restore("gone", 500000);
		usage.setCurrent(counts("old", 2, "new", 5));
		assertEquals(Arrays.asList("new", "old", "gone"), Arrays.asList(usage.ranking().get(0).getKey(), usage.ranking().get(1).getKey(), usage.ranking().get(2).getKey()));
		assertEquals(5, usage.currentTrains("new"));
		// As many trains now: the past decides
		usage.setCurrent(counts("old", 3, "new", 3));
		assertEquals("old", usage.ranking().get(0).getKey());
		// Known at once, without waiting for a count over time (a player joining a fresh server)
		final TrainUsage fresh = new TrainUsage();
		fresh.setCurrent(counts("a", 1, "b", 4));
		assertEquals("b", fresh.ranking().get(0).getKey());
		assertEquals(0, fresh.ranking().get(0).getValue(), 0);
	}

	@Test
	void pastFades() {
		final TrainUsage usage = new TrainUsage();
		usage.restore("a", 1000);
		usage.update(Collections.emptyMap(), 0);
		// A week of steps no longer than a minute each: halved
		long millis = 0;
		for (int i = 0; i < 7 * 24 * 60; i++) {
			millis += 60_000;
			usage.update(Collections.emptyMap(), millis);
		}
		assertEquals(500, usage.ranking().get(0).getValue(), 1);
		// A long gap (the server stopped) counts as a minute only
		usage.update(Collections.emptyMap(), millis + 30L * 24 * 3600 * 1000);
		assertEquals(500, usage.ranking().get(0).getValue(), 1);
	}

	@Test
	void writtenWhenChangedAndOrderSent() {
		final TrainUsage usage = new TrainUsage();
		assertNull(usage.takeIfChanged());
		usage.update(counts("a", 1), 0);
		usage.update(counts("a", 1), 20_000);
		assertNotNull(usage.takeIfChanged());
		assertNull(usage.takeIfChanged(), "taken");
		assertTrue(usage.orderChanged());
		usage.update(counts("a", 1), 40_000);
		assertFalse(usage.orderChanged(), "the same order");
		usage.update(counts("a", 1, "b", 5), 60_000);
		assertTrue(usage.orderChanged());
	}

	@Test
	void keptInTheDatabase() throws Exception {
		final Map<String, Double> scores = new HashMap<>();
		scores.put("mtr_custom_train_x", 1234.5);
		scores.put("sp1900", 8.0);
		RailwayDataSqliteStore store = new RailwayDataSqliteStore(folder, new String[0]);
		try {
			store.writeTrainUsage(scores);
			store.commit();
		} finally {
			store.close();
		}
		final TrainUsage read = new TrainUsage();
		store = new RailwayDataSqliteStore(folder, new String[0]);
		try {
			store.forEachTrainUsage(read::restore);
		} finally {
			store.close();
		}
		assertEquals("mtr_custom_train_x", read.ranking().get(0).getKey());
		assertEquals(1234.5, read.ranking().get(0).getValue(), 1E-9);
		assertEquals(2, read.ranking().size());
	}
}
