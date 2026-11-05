package transitsim.network;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Long track cut into block sections by the braking distance at its speed limit, the same way every time and from both
 * directions (decoupling document 5.4, 2b).
 */
class BlockSectionsTest {

	/**
	 * 80 km/h in blocks per tick: 22.2 m/s, braking at 1 m/s² in about 247 blocks, so sections of the shortest, 400.
	 */
	private static final float KMH_80 = (float) (80 / 3.6 / 20);
	/**
	 * 160 km/h: braking in about 988 blocks.
	 */
	private static final float KMH_160 = (float) (160 / 3.6 / 20);
	private static final UUID RAIL = RailKeys.of(1, 2);

	@Test
	void aRailIsCutByTheBrakingDistanceAtItsLimit() {
		assertEquals(987.7, BlockSections.getSectionLength(KMH_160), 0.1);
		// Slower track has sections of at least the shortest length
		assertEquals(BlockSections.minLength, BlockSections.getSectionLength(KMH_80), 1E-9);
		assertEquals(1, BlockSections.getPieceCount(380, KMH_80));
		assertEquals(3, BlockSections.getPieceCount(1000, KMH_80));
		assertEquals(2, BlockSections.getPieceCount(1000, KMH_160));
		// A platform or siding, given no speed limit to cut by, is never cut
		assertEquals(1, BlockSections.getPieceCount(1000, -1));
	}

	@Test
	void bothDirectionsSeeTheSamePieces() {
		// The first 10 blocks one way are the last piece the other way
		final List<UUID> forwards = BlockSections.getPieces(RAIL, 1000, KMH_80, true, 0, 10);
		final List<UUID> backwards = BlockSections.getPieces(RAIL, 1000, KMH_80, false, 990, 1000);
		assertEquals(Collections.singletonList(RAIL), forwards);
		assertEquals(forwards, backwards);
		// The whole rail is every piece, each once
		final List<UUID> all = BlockSections.getPieces(RAIL, 1000, KMH_80, true, 0, 1000);
		assertEquals(3, new HashSet<>(all).size());
		assertEquals(new HashSet<>(all), new HashSet<>(BlockSections.getPieces(RAIL, 1000, KMH_80, false, 0, 1000)));
		// A rail in one piece is its own key
		assertEquals(Collections.singletonList(RAIL), BlockSections.getPieces(RAIL, 200, KMH_80, false, 50, 60));
		// Pieces start where the path reaches them
		assertEquals(1000 / 3.0, BlockSections.getPieceStarts(RAIL, 1000, KMH_80, true).get(1).getKey(), 1E-9);
	}

	@Test
	void aSignalBlockIsOneSectionHoweverLongWithEveryPieceOfItsRails() {
		// Two joined rails, one of them 1000 blocks long and so three pieces, painted as one block
		final UUID a = RailKeys.of(1, 2), b = RailKeys.of(2, 3);
		final BlockSections.RailInfo info = new BlockSections.RailInfo() {
			@Override
			public double getLength(UUID rail) {
				return rail.equals(a) ? 1000 : 200;
			}

			@Override
			public float getMaxSpeed(UUID rail) {
				return KMH_80;
			}
		};
		final BlockSections sections = new BlockSections(Collections.singletonList(Arrays.asList(b, a)), info);
		final List<UUID> section = sections.getSection(b);
		assertEquals(4, section.size());
		assertTrue(section.contains(BlockSections.getPieceKey(a, 2)));
		assertEquals(section, sections.getSection(BlockSections.getPieceKey(a, 1)));
		assertEquals(2, (int) sections.getPiece(BlockSections.getPieceKey(a, 2)).getValue());
		assertNull(sections.getSection(RailKeys.of(7, 8)));
	}
}
