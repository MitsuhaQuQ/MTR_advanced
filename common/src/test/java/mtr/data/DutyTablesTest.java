package mtr.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.JDBC;
import transitsim.plan.DepotDuties;
import transitsim.plan.DutyLayers;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Depots' duties kept as layers in tables of their own (运用表复用和索引, 运用补完 step 1): the same body or group once
 * however many depots run it, found from a service or a group, and swept when no depot refers to it.
 */
class DutyTablesTest {

	@TempDir
	Path folder;

	private static DepotDuties duties(long sidingId, int... services) {
		final DepotDuties duties = new DepotDuties();
		duties.diagramMode = true;
		final List<DepotDuties.Slot> slots = new ArrayList<>();
		for (final int service : services) {
			final DepotDuties.Slot slot = new DepotDuties.Slot();
			slot.id = DepotDuties.defaultId(sidingId, slots.size());
			final DepotDuties.DayDuty day = new DepotDuties.DayDuty();
			day.weekdays = DepotDuties.ALL_WEEKDAYS;
			day.segments.add(new DepotDuties.Segment(1, service, 0, -1));
			day.segments.add(new DepotDuties.Segment(2, service + 1800000, 0, -1));
			day.waits.add(DepotDuties.DayDuty.WAIT_PLATFORM);
			day.outLeadMillis = 60000;
			slot.days.add(day);
			slots.add(slot);
		}
		duties.sidings.put(sidingId, slots);
		return duties;
	}

	private static DutyLayers layers(DepotDuties duties) {
		return DutyLayers.of(duties, sidingId -> "pool");
	}

	private long count(String table) throws Exception {
		try (final Connection connection = JDBC.createConnection(JDBC.PREFIX + folder.resolve("mtr.db"), new Properties()); final Statement statement = connection.createStatement(); final ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
			return resultSet.getLong(1);
		}
	}

	@Test
	void theSameDutiesAreStoredOnceAndFoundFromAServiceOrAGroup() throws Exception {
		final RailwayDataSqliteStore store = new RailwayDataSqliteStore(folder, new String[0]);
		// Two depots running the same two duties as a template, a third with one of its own
		final DutyLayers shared = layers(duties(10, 21600000, 25200000));
		store.writeDutyLayers(1, 11, null, shared);
		store.writeDutyLayers(2, 22, layers(duties(20, 21600000, 25200000)), null);
		store.writeDutyLayers(3, 33, null, layers(duties(30, 28800000)));
		store.commit();
		final long groupKey = shared.groups.keySet().iterator().next();
		assertEquals(Arrays.asList(1L, 2L), store.findDepotsUsingGroup(groupKey));
		// Three bodies in all, two groups
		final List<long[]> running = store.findDutiesRunning(1, 21600000);
		assertEquals(2, running.size(), "the 06:00 service is run by a duty of depot 1 and one of depot 2");
		assertEquals(1, running.get(0)[0]);
		assertEquals(1, running.get(0)[1], "planned");
		assertEquals(2, running.get(1)[0]);
		assertEquals(0, running.get(1)[1], "set by players");
		store.close();
		assertEquals(3, count("duty_bodies"));
		assertEquals(2, count("duty_groups"));

		// The fingerprints come back to tell which depots' layers are behind
		final RailwayDataSqliteStore reopened = new RailwayDataSqliteStore(folder, new String[0]);
		final Map<Long, Long> fingerprints = new TreeMap<>();
		reopened.loadDutyFingerprints(fingerprints::put);
		assertEquals(33L, fingerprints.get(3L));
		// Depot 3 gone: its body and group are swept; the shared ones stay while depot 2 has them
		reopened.deleteDutyLayers(3);
		reopened.deleteDutyLayers(1);
		reopened.sweepDutyLayers();
		reopened.commit();
		assertEquals(Collections.singletonList(2L), reopened.findDepotsUsingGroup(groupKey));
		assertTrue(reopened.findDutiesRunning(1, 28800000).isEmpty());
		reopened.close();
		assertEquals(2, count("duty_bodies"));
		assertEquals(1, count("duty_groups"));
		assertEquals(2, count("duty_assignments"));
	}

	@Test
	void writingADepotAgainReplacesItsAssignments() throws Exception {
		final RailwayDataSqliteStore store = new RailwayDataSqliteStore(folder, new String[0]);
		store.writeDutyLayers(1, 1, null, layers(duties(10, 21600000, 25200000)));
		store.writeDutyLayers(1, 2, null, layers(duties(10, 21600000)));
		store.sweepDutyLayers();
		store.commit();
		store.close();
		assertEquals(1, count("duty_assignments"));
		assertEquals(1, count("duty_bodies"));
		assertEquals(2, count("duty_body_services"));
	}
}
