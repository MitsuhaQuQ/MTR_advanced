package transitsim.network;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Logical lines of the track and the operating lines that run on them (web layers, 2026-11-09).
 */
class LogicalLinesTest {

	private static final long A = 1, B = 2, LINK = 3, SPARE = 4;

	@Test
	void anOperatingLineRunsOnTheLogicalLinesOfItsPlatformsOrThoseChosenForIt() {
		// Line X along the top, line Y along the bottom, a crossover between them, and a siding branching off the end of the
		// bottom, where the bottom goes on, in no line
		final TrackSections.Rail x1 = rail(0, 0, 20, 0, SectionType.PLATFORM, 1);
		final TrackSections.Rail xTrack = rail(20, 0, 200, 0, SectionType.NORMAL, 0);
		final TrackSections.Rail y1 = rail(0, 10, 20, 10, SectionType.PLATFORM, 3);
		final TrackSections.Rail yTrack = rail(20, 10, 200, 10, SectionType.NORMAL, 0);
		final TrackSections.Rail crossover = rail(60, 0, 80, 10, SectionType.NORMAL, 0);
		final TrackSections.Rail spare = rail(200, 10, 260, 40, SectionType.NORMAL, 0);
		final TrackSections.Rail onwards = rail(200, 10, 300, 10, SectionType.NORMAL, 0);
		final TrackSections sections = sections(x1, xTrack, y1, yTrack, crossover, spare, onwards);
		final TrackSections.Snapshot snapshot = sections.snapshot();
		final Map<Long, List<String>> platformLines = new HashMap<>();
		platformLines.put(1L, Collections.singletonList("X"));
		platformLines.put(3L, Collections.singletonList("Y"));

		final Map<UUID, Long> railLines = new HashMap<>();
		railLines.put(x1.getKey(), A);
		railLines.put(xTrack.getKey(), A);
		railLines.put(y1.getKey(), B);
		railLines.put(yTrack.getKey(), B);
		railLines.put(crossover.getKey(), LINK);
		final Set<Long> connecting = Collections.singleton(LINK);

		// None chosen: each line runs on the logical line of its platforms
		final LogicalLines.Result auto = LogicalLines.compute(snapshot, railLines, connecting, Collections.emptyMap(), platformLines);
		assertEquals(Collections.singleton(A), auto.allowed.get("X"));
		assertEquals(Collections.singleton("X"), auto.owners.get(sections.getSectionOf(xTrack.getKey())));
		assertEquals(Collections.singleton("Y"), auto.owners.get(sections.getSectionOf(yTrack.getKey())));
		assertFalse(TrackOwnership.mayRun(auto.owners.get(sections.getSectionOf(yTrack.getKey())), "X"));
		// The connecting line and the track in no line: every line
		assertFalse(auto.owners.containsKey(sections.getSectionOf(crossover.getKey())));
		assertEquals(LINK, auto.sectionLines.get(sections.getSectionOf(crossover.getKey())));
		assertTrue(TrackOwnership.mayRun(auto.owners.get(sections.getSectionOf(spare.getKey())), "X"));
		assertFalse(auto.sectionLines.containsKey(sections.getSectionOf(spare.getKey())));

		// Y chosen to run on both: X's track is open to it too, and only to the two
		final Map<String, List<Long>> chosen = new HashMap<>();
		chosen.put("Y", Arrays.asList(A, B));
		final LogicalLines.Result both = LogicalLines.compute(snapshot, railLines, connecting, chosen, platformLines);
		assertEquals(new TreeSet<>(Arrays.asList("X", "Y")), both.owners.get(sections.getSectionOf(xTrack.getKey())));
		assertEquals(Collections.singleton("Y"), both.owners.get(sections.getSectionOf(yTrack.getKey())));
		// A line chosen for nothing it has platforms on keeps to what was chosen
		chosen.put("X", Collections.singletonList(B));
		final LogicalLines.Result moved = LogicalLines.compute(snapshot, railLines, connecting, chosen, platformLines);
		assertEquals(Collections.singleton("Y"), moved.owners.get(sections.getSectionOf(xTrack.getKey())));
		assertEquals(new TreeSet<>(Arrays.asList("X", "Y")), moved.owners.get(sections.getSectionOf(yTrack.getKey())));
	}

	@Test
	void aLogicalLineNoOperatingLineRunsOnIsClosedAndASectionHasOneLine() {
		final TrackSections.Rail x1 = rail(0, 0, 20, 0, SectionType.PLATFORM, 1);
		final TrackSections.Rail first = rail(20, 0, 100, 0, SectionType.NORMAL, 0);
		final TrackSections.Rail second = rail(100, 0, 180, 0, SectionType.NORMAL, 0);
		final TrackSections sections = sections(x1, first, second);
		final TrackSections.Snapshot snapshot = sections.snapshot();
		final Map<Long, List<String>> platformLines = Collections.singletonMap(1L, Collections.singletonList("X"));
		final long section = sections.getSectionOf(first.getKey());
		assertEquals(section, (long) sections.getSectionOf(second.getKey()), "one section");

		// Two rails of one section put in two lines: the one with the lower key counts
		final Map<UUID, Long> railLines = new HashMap<>();
		railLines.put(first.getKey(), SPARE);
		railLines.put(second.getKey(), B);
		final long expected = first.getKey().compareTo(second.getKey()) < 0 ? SPARE : B;
		final LogicalLines.Result result = LogicalLines.compute(snapshot, railLines, Collections.emptySet(), Collections.emptyMap(), platformLines);
		assertEquals(expected, result.sectionLines.get(section));
		// X's platform is in no line, so it runs on none of them: the section is closed to every line
		assertEquals(Collections.emptySet(), result.owners.get(section));
		assertFalse(TrackOwnership.mayRun(result.owners.get(section), "X"));
		// A connecting line's route still runs anywhere
		assertTrue(TrackOwnership.mayRun(result.owners.get(section), null));
		// A rail no longer there is left alone
		final Map<UUID, Long> gone = Collections.singletonMap(RailKeys.of(pos(900, 0), pos(950, 0)), A);
		assertTrue(LogicalLines.compute(snapshot, gone, Collections.emptySet(), Collections.emptyMap(), platformLines).sectionLines.isEmpty());
	}

	private static TrackSections sections(TrackSections.Rail... rails) {
		final Map<UUID, TrackSections.Rail> map = new HashMap<>();
		for (final TrackSections.Rail rail : rails) {
			map.put(rail.getKey(), rail);
		}
		final TrackSections sections = new TrackSections();
		sections.sync(map, new HashMap<>());
		return sections;
	}

	private static long pos(int x, int z) {
		return (x + 100000L) * 1000000L + z + 100000L;
	}

	private static TrackSections.Rail rail(int x1, int z1, int x2, int z2, SectionType type, long savedRailId) {
		final int facing1 = (int) Math.round(Math.toDegrees(Math.atan2(z2 - z1, x2 - x1)) * 10);
		final int facing2 = (int) Math.round(Math.toDegrees(Math.atan2(z1 - z2, x1 - x2)) * 10);
		final long node1 = pos(x1, z1);
		final long node2 = pos(x2, z2);
		final long shape = node1 < node2 ? (long) facing1 << 32 | facing2 & 0xFFFFFFFFL : (long) facing2 << 32 | facing1 & 0xFFFFFFFFL;
		return new TrackSections.Rail(node1, node2, Math.hypot(x2 - x1, z2 - z1), shape, type == SectionType.PLATFORM ? 80 : 120, type == SectionType.NORMAL, type, true, true, savedRailId);
	}
}
