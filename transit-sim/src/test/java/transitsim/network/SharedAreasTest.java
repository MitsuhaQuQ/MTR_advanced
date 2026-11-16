package transitsim.network;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Track run on by more than one planning unit (2026-11-16, the user's world: two depots planned apart shared single
 * track and a terminal platform, and their trains kept each other waiting).
 */
class SharedAreasTest {

	@Test
	void railsOfTwoUnitsMakeOneAreaWhereTheyMeet() {
		final SharedAreas sharedAreas = new SharedAreas();
		// Unit A runs 1-2-3-4-5, unit B joins at 3 from 10 and runs to 5 and back, the other way round
		sharedAreas.addPath("A", new long[]{1, 2, 3, 4, 5});
		sharedAreas.addPath("B", new long[]{10, 3, 4, 5});
		sharedAreas.addPath("B", new long[]{5, 4, 3, 10});
		final List<SharedAreas.Area> areas = sharedAreas.result();
		assertEquals(1, areas.size());
		assertEquals(Arrays.asList("A", "B"), Arrays.asList(areas.get(0).units.toArray()));
		assertEquals(Arrays.asList(new SharedAreas.Rail(3, 4), new SharedAreas.Rail(4, 5)), areas.get(0).rails);
	}

	@Test
	void separateStretchesAreSeparateAreasAndOneUnitAloneIsNone() {
		final SharedAreas sharedAreas = new SharedAreas();
		sharedAreas.addPath("A", new long[]{1, 2, 3, 4, 5, 6});
		// B runs 1-2, then its own way 2-20-5, then 5-6: two stretches apart
		sharedAreas.addPath("B", new long[]{1, 2, 20, 5, 6});
		// A's trains on several paths of their own are no sharing
		sharedAreas.addPath("A", new long[]{30, 31});
		sharedAreas.addPath("A", new long[]{31, 30});
		final List<SharedAreas.Area> areas = sharedAreas.result();
		assertEquals(2, areas.size());
		assertEquals(new SharedAreas.Rail(1, 2), areas.get(0).rails.get(0));
		assertEquals(new SharedAreas.Rail(5, 6), areas.get(1).rails.get(0));
		assertTrue(sharedAreas.getUnits(31, 30).contains("A"));
	}

	@Test
	void aThirdUnitOnPartOfAnAreaIsListedWithIt() {
		final SharedAreas sharedAreas = new SharedAreas();
		sharedAreas.addPath("A", new long[]{1, 2, 3});
		sharedAreas.addPath("B", new long[]{1, 2, 3});
		sharedAreas.addPath("C", new long[]{9, 2});
		sharedAreas.addPath("C", new long[]{2, 3});
		final List<SharedAreas.Area> areas = sharedAreas.result();
		assertEquals(1, areas.size());
		assertEquals(Arrays.asList("A", "B", "C"), Arrays.asList(areas.get(0).units.toArray()));
	}

	@Test
	void theSameWhateverOrderPathsComeIn() {
		final SharedAreas first = new SharedAreas();
		first.addPath("A", new long[]{1, 2, 3});
		first.addPath("B", new long[]{3, 2, 7});
		final SharedAreas second = new SharedAreas();
		second.addPath("B", new long[]{3, 2, 7});
		second.addPath("A", new long[]{1, 2, 3});
		assertEquals(first.result().get(0).describe(), second.result().get(0).describe());
	}
}
