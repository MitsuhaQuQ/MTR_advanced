package mtr.data;

import org.junit.jupiter.api.Test;
import transitsim.plan.DepotDuties;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Depots sharing a route leave each other's services alone in their auto duties, so no service is run twice.
 */
class SharedTakenTest {

	private static final int EIGHT = 8 * 3600 * 1000;
	private static final int NINE = 9 * 3600 * 1000;

	@Test
	void theEarlierDepotsAutoDutiesAndAnyDepotsOwnDutiesAreLeftAlone() {
		final Depot first = depot(1, 10);
		final Depot second = depot(2, 10, 11);
		final Depot elsewhere = depot(3, 12);
		setDuties(first.autoDuties, new DepotDuties.Segment(10, EIGHT, 0, -1));
		setDuties(second.autoDuties, new DepotDuties.Segment(10, NINE, 0, -1), new DepotDuties.Segment(11, NINE, 0, -1));
		setDuties(elsewhere.autoDuties, new DepotDuties.Segment(12, EIGHT, 0, -1));
		final DataCache dataCache = dataCache(first, second, elsewhere);

		// Auto duties go by ID: the first takes what it likes, the second leaves the first's
		assertEquals(Collections.emptySet(), first.getSharedTaken(dataCache));
		assertEquals(set("10@" + EIGHT), second.getSharedTaken(dataCache));

		// Duties set by hand come first whatever the ID, and only the routes in common count
		setDuties(second.duties, new DepotDuties.Segment(10, NINE, 0, -1), new DepotDuties.Segment(11, NINE, 0, -1));
		second.duties.diagramMode = true;
		assertEquals(set("10@" + NINE), first.getSharedTaken(dataCache));
	}

	@Test
	void otherKindsOfVehicleDontCount() {
		final Depot trains = depot(1, 10);
		final Depot boats = new Depot(2, TransportMode.BOAT);
		boats.routeIds.add(10L);
		setDuties(boats.autoDuties, new DepotDuties.Segment(10, EIGHT, 0, -1));
		assertEquals(Collections.emptySet(), trains.getSharedTaken(dataCache(trains, boats)));
	}

	private static Depot depot(long id, long... routeIds) {
		final Depot depot = new Depot(id, TransportMode.TRAIN);
		Arrays.stream(routeIds).forEach(depot.routeIds::add);
		return depot;
	}

	private static void setDuties(DepotDuties duties, DepotDuties.Segment... segments) {
		final DepotDuties.DayDuty day = new DepotDuties.DayDuty();
		day.weekdays = DepotDuties.ALL_WEEKDAYS;
		day.segments.addAll(Arrays.asList(segments));
		final DepotDuties.Slot slot = new DepotDuties.Slot();
		slot.days.add(day);
		duties.sidings.put(100L, Collections.singletonList(slot));
		duties.diagramMode = true;
	}

	private static DataCache dataCache(Depot... depots) {
		final DataCache dataCache = new DataCache(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(Arrays.asList(depots)), new HashSet<>());
		for (final Depot depot : depots) {
			dataCache.depotIdMap.put(depot.id, depot);
		}
		return dataCache;
	}

	private static Set<String> set(String... values) {
		return new TreeSet<>(Arrays.asList(values));
	}
}
