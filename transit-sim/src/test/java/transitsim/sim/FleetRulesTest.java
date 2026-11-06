package transitsim.sim;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one copy of the rules of pools of sidings and their trains (S7), as both hosts go by them.
 */
class FleetRulesTest {

	@Test
	void aSidingWithATrainStandingMakesNone() {
		assertEquals(FleetRules.Make.NONE, FleetRules.whatToMake(false, false, false, 4, 1, 3, 1, 2, 1));
	}

	@Test
	void aTrainKeptInTheShedStandsFirst() {
		// Even with the pool full: it is one of the pool's already
		assertEquals(FleetRules.Make.FROM_SHED, FleetRules.whatToMake(true, false, false, 2, 2, 3, 1, 2, 1));
		assertEquals(FleetRules.Make.FROM_SHED, FleetRules.whatToMake(false, true, false, -1, 5, 3, 1, -1, 0));
	}

	@Test
	void aPoolGivenItsTrainsMakesNoMore() {
		assertEquals(FleetRules.Make.NEW, FleetRules.whatToMake(true, false, true, 3, 2, 9, 0, 2, 1));
		assertEquals(FleetRules.Make.NONE, FleetRules.whatToMake(true, false, true, 3, 3, 9, 0, 2, 1));
		// Not even for a siding with none: the others have them
		assertEquals(FleetRules.Make.NONE, FleetRules.whatToMake(true, true, true, 3, 3, 9, 0, 2, 0));
		// Nor where the siding is full
		assertEquals(FleetRules.Make.NONE, FleetRules.whatToMake(true, false, true, 3, 1, 9, 2, 2, 2));
	}

	@Test
	void asManyAsNeededIsAsManyAsTheDutiesEachSidingWithOneAtLeast() {
		assertEquals(FleetRules.Make.NEW, FleetRules.whatToMake(true, false, true, -1, 2, 3, 0, -1, 1));
		assertEquals(FleetRules.Make.NONE, FleetRules.whatToMake(true, false, true, -1, 3, 3, 0, -1, 1));
		// A siding with no train has one, the duties met or none at all
		assertEquals(FleetRules.Make.NEW, FleetRules.whatToMake(true, true, true, -1, 3, 3, 0, -1, 0));
		assertEquals(FleetRules.Make.NEW, FleetRules.whatToMake(true, true, true, -1, 0, 0, 0, 2, 0));
		// Within what the siding holds
		assertEquals(FleetRules.Make.NONE, FleetRules.whatToMake(true, false, true, -1, 1, 3, 0, 2, 2));
	}

	@Test
	void roomAndFleets() {
		assertTrue(FleetRules.hasRoom(5, 5, -1));
		assertTrue(FleetRules.hasRoom(1, 0, 2));
		assertFalse(FleetRules.hasRoom(1, 1, 2));
		assertEquals(7, FleetRules.sumFleets(Arrays.asList(3, 4)));
		assertEquals(-1, FleetRules.sumFleets(Arrays.asList(3, -1, 4)));
		assertEquals(0, FleetRules.sumFleets(Collections.emptyList()));
		assertTrue(FleetRules.keepsSecondStanding(true));
		assertFalse(FleetRules.keepsSecondStanding(false));
	}

	@Test
	void aPoolIsItsDepotsSidingsOfItsKeyThenThoseOfItsLine() {
		// id, depot, pool key, line pool
		final long[][] sidings = {{1, 10, 0, 0}, {2, 10, 0, 0}, {3, 10, 1, 0}, {4, 20, 0, 0}, {5, 30, 0, 1}};
		final Map<Long, long[]> byId = new HashMap<>();
		for (final long[] siding : sidings) {
			byId.put(siding[0], siding);
		}
		final List<Long> all = Arrays.asList(1L, 2L, 3L, 4L, 5L);
		final List<Long> pool = FleetRules.linePool(1L, all, id -> byId.get(id)[1], id -> byId.get(id)[2], id -> byId.get(id)[1] == 30 || byId.get(id)[1] == 10 ? "unit|0" : "");
		assertEquals(Arrays.asList(1L, 2L, 5L), pool);
		// The same whatever order the candidates come in
		final List<Long> shuffled = Arrays.asList(5L, 3L, 2L, 4L, 1L);
		assertEquals(new HashSet<>(pool), new HashSet<>(FleetRules.linePool(1L, shuffled, id -> byId.get(id)[1], id -> byId.get(id)[2], id -> byId.get(id)[1] == 30 || byId.get(id)[1] == 10 ? "unit|0" : "")));
		// No line pool: its depot's alone
		assertEquals(Arrays.asList(4L), FleetRules.linePool(4L, all, id -> byId.get(id)[1], id -> byId.get(id)[2], id -> ""));
	}
}
