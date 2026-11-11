package transitsim.fare;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fares by distance (design document 6.13).
 */
class FareTest {

	private static final long KM = FareLevels.BLOCKS_PER_KILOMETRE;

	@Test
	void theFormulaMakesATierEveryUnitDistance() {
		final FareLevels levels = new FareLevels();
		levels.startFare = 2;
		levels.initialBlocks = 8 * KM;
		levels.unitBlocks = 4 * KM;
		levels.unitFare = 1;
		final FareLevels.Tiers tiers = levels.formulaTiers(20 * KM);
		assertArrayEquals(new long[]{8 * KM, 12 * KM, 16 * KM}, tiers.bounds);
		assertArrayEquals(new int[]{2, 3, 4, 5}, tiers.prices);
		// The formula itself: start + ⌈max(0, d − initial) ÷ unit⌉ × unit fare
		assertEquals(2, levels.getFare(8 * KM, 20 * KM));
		assertEquals(3, levels.getFare(8 * KM + 1, 20 * KM));
		assertEquals(4, levels.getFare(16 * KM, 20 * KM));
		assertEquals(5, levels.getFare(19 * KM, 20 * KM));
		// Past the longest distance, the last tier is the cap
		assertEquals(5, levels.getFare(90 * KM, 20 * KM));
	}

	@Test
	void tiersSetByHandAreKeptAndCapped() {
		// The document's example: bounds 8, 12, 24, fares 2, 3, 4, 5
		final FareLevels levels = new FareLevels();
		levels.setTiers(new long[]{8 * KM, 12 * KM, 24 * KM}, new int[]{2, 3, 4, 5});
		assertEquals(2, levels.getFare(5 * KM, 0));
		assertEquals(3, levels.getFare(10 * KM, 0));
		assertEquals(4, levels.getFare(24 * KM, 0));
		assertEquals(5, levels.getFare(30 * KM, 0));
		levels.cap = 4;
		assertEquals(4, levels.getFare(30 * KM, 0));
	}

	@Test
	void theSameStationCostsTheStartFareOrTheOvertimeFine() {
		final FareLevels levels = new FareLevels();
		levels.startFare = 3;
		assertEquals(3, levels.getSameStationFare(500));
		levels.overtime = true;
		levels.overtimeMinutes = 120;
		levels.overtimeFine = 50;
		assertEquals(3, levels.getSameStationFare(100));
		assertEquals(50, levels.getSameStationFare(121));
	}

	/**
	 * Stations 1 to 4 along group A; group B joins 1 and 4 shorter.
	 */
	private static FareNetwork network() {
		return new FareNetwork(Arrays.asList(
				new FareNetwork.Leg("A", 1, 2, 5 * KM),
				new FareNetwork.Leg("A", 2, 3, 5 * KM),
				new FareNetwork.Leg("A", 3, 4, 5 * KM),
				new FareNetwork.Leg("B", 1, 4, 3 * KM),
				new FareNetwork.Leg("B", 4, 5, 2 * KM)
		));
	}

	@Test
	void theFarePathCrossesTheFewestGroupsBeforeItIsShort() {
		final FareNetwork network = network();
		// Entering at a gate of A, leaving at a gate of A: all the way along A, though B is shorter
		final FareNetwork.Path path = network.find(1, 4, Collections.singleton("A"), Collections.singleton("A"));
		assertEquals(0, path.crossings);
		assertEquals(15 * KM, path.getBlocks());
		// Any group: B alone is shorter and crosses none
		final FareNetwork.Path any = network.find(1, 4, Collections.emptySet(), Collections.emptySet());
		assertEquals(0, any.crossings);
		assertEquals(3 * KM, any.getBlocks());
		assertEquals("B", any.parts.get(0).group);
		// From A's station 2 to B's station 5: one crossing either way, back along A to 1 then B is the shorter
		final FareNetwork.Path across = network.find(2, 5, Collections.singleton("A"), Collections.singleton("B"));
		assertEquals(1, across.crossings);
		assertEquals(2, across.parts.size());
		assertEquals("A", across.parts.get(0).group);
		assertEquals(5 * KM, across.parts.get(0).blocks);
		assertEquals(5 * KM, across.parts.get(1).blocks);
	}

	@Test
	void eachPartIsChargedByItsOwnGroup() {
		final FareNetwork network = network();
		final Map<String, FareLevels> levels = new HashMap<>();
		final FareLevels a = new FareLevels();
		a.setTiers(new long[]{6 * KM}, new int[]{2, 4});
		final FareLevels b = new FareLevels();
		b.setTiers(new long[0], new int[]{7});
		levels.put("A", a);
		levels.put("B", b);
		final FareTrip.Result result = FareTrip.charge(network, levels, gate(2, false, "A"), gate(5, false, "B"), 10);
		// A for 5 km, then B
		assertEquals(2 + 7, result.fare);
		assertEquals("B", result.group);
	}

	@Test
	void sharedGatesAtBothEndsTakeTheCheapestGroup() {
		final FareNetwork network = network();
		final Map<String, FareLevels> levels = new HashMap<>();
		final FareLevels a = new FareLevels();
		a.setTiers(new long[0], new int[]{3});
		final FareLevels b = new FareLevels();
		b.setTiers(new long[0], new int[]{9});
		levels.put("A", a);
		levels.put("B", b);
		// Station 1 and 4 have both groups: A is cheaper, though B is shorter
		final FareTrip.Result shared = FareTrip.charge(network, levels, gate(1, true, "A", "B"), gate(4, true, "A", "B"), 10);
		assertEquals(3, shared.fare);
		assertEquals("A", shared.group);
		// Leaving at a gate of B: B decides
		final FareTrip.Result exitB = FareTrip.charge(network, levels, gate(1, true, "A", "B"), gate(4, false, "B"), 10);
		assertEquals(9, exitB.fare);
		// The same station: the exit gate's group's start fare
		final FareTrip.Result same = FareTrip.charge(network, levels, gate(1, true, "A", "B"), gate(1, false, "B"), 10);
		assertEquals(9, same.fare);
		assertNull(same.path);
	}

	@Test
	void theLongestDistanceOfAGroupIsAlongItsOwnTrack() {
		assertEquals(15 * KM, network().getLongestBlocks("A"));
		assertEquals(5 * KM, network().getLongestBlocks("B"));
	}

	@Test
	void faresAreNeverNegative() {
		// A negative fare would pay a player for a trip: values read from a save or a packet are kept in range
		final FareLevels levels = new FareLevels();
		levels.startFare = -5;
		levels.unitFare = -1;
		levels.initialBlocks = -100;
		levels.unitBlocks = -3;
		levels.cap = -9;
		levels.overtimeMinutes = -1;
		levels.overtimeFine = -20;
		levels.setTiers(new long[]{6 * KM, -KM}, new int[]{-2, 3, -4});
		levels.clamp();
		assertEquals(0, levels.startFare);
		assertEquals(0, levels.unitFare);
		assertEquals(0, levels.initialBlocks);
		assertEquals(1, levels.unitBlocks);
		assertEquals(-1, levels.cap);
		assertEquals(0, levels.overtimeMinutes);
		assertEquals(0, levels.overtimeFine);
		assertArrayEquals(new long[]{0, 6 * KM}, levels.bounds);
		assertArrayEquals(new int[]{0, 3, 0}, levels.prices);
		assertTrue(levels.getFare(90 * KM, 0) >= 0);
	}

	@Test
	void tiersNotSetByHandDontCountForEquality() {
		// They are not saved or sent, so a group's fares read back must still equal what was written
		final FareLevels a = new FareLevels();
		final FareLevels b = new FareLevels();
		b.bounds = new long[]{KM};
		b.prices = new int[]{2, 9};
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		b.manualTiers = true;
		assertNotEquals(a, b);
	}

	@Test
	void stationsNotJoinedHaveNoFare() {
		final FareTrip.Result result = FareTrip.charge(network(), new HashMap<>(), gate(1, false, "A"), gate(99, false, "A"), 0);
		assertEquals(-1, result.fare);
	}

	private static FareTrip.Gate gate(long station, boolean shared, String... groups) {
		return new FareTrip.Gate(station, new HashSet<>(Arrays.asList(groups)), shared);
	}
}
