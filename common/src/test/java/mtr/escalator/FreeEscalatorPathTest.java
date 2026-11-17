package mtr.escalator;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The shape of a free escalator (0.100.0) */
class FreeEscalatorPathTest {

	private static final double D = FreeEscalatorPath.DECK;

	private static double[] p(double x, double y, double z) {
		return new double[]{x + 0.5, y + 1 + D, z + 0.5};
	}

	private static FreeEscalatorPath build(double width, double[]... points) {
		final FreeEscalatorPath.Result result = FreeEscalatorPath.build(Arrays.asList(points), width);
		assertNull(result.error, () -> result.error + " " + Arrays.toString(result.arguments));
		return result.path;
	}

	@Test
	void walkwayOnTheFloor() {
		// Flat, 8 blocks east: a walkway lying just over the blocks marked, one block of collision over each
		final FreeEscalatorPath path = build(1, p(0, 0, 0), p(8, 0, 0));
		assertFalse(path.stepped);
		assertEquals(8, path.length, 1E-9);
		assertEquals(1 + D, path.height(3), 1E-9);
		final List<int[]> cells = path.cells();
		assertEquals(9, cells.size(), "the blocks over x = 0 to 8");
		for (final int[] cell : cells) {
			assertEquals(1, cell[1], "over the floor, not in it");
			assertEquals(0, cell[2]);
		}
		final byte[] heights = path.columnHeights(4, 1, 0);
		assertNotNull(heights);
		for (final byte height : heights) {
			assertEquals(2, height, "two sixteenths thick");
		}
	}

	@Test
	void escalatorUpAndBends() {
		// Flat 3, up 6 over 12 (1 in 2), flat 3: an escalator, level treads, bends smoothed
		final FreeEscalatorPath path = build(1, p(0, 0, 0), p(3, 0, 0), p(15, 6, 0), p(18, 6, 0));
		assertTrue(path.stepped);
		assertEquals(18, path.length, 1E-9);
		assertEquals(1 + D, path.height(0), 1E-9);
		assertEquals(7 + D, path.height(18), 1E-9);
		assertEquals(1 + D + 3, path.height(9), 1E-9, "the middle of the slope");
		// Smooth through the bends: the height and grade change little over a small step
		double previousHeight = path.height(0), previousGrade = path.grade(0);
		for (double s = 0.05; s <= 18; s += 0.05) {
			assertTrue(Math.abs(path.height(s) - previousHeight) < 0.05, "no jump at " + s);
			assertTrue(Math.abs(path.grade(s) - previousGrade) < 0.06, "no kink at " + s);
			previousHeight = path.height(s);
			previousGrade = path.grade(s);
		}
		// Treads level, each over the run, a riser up to each from the one behind
		final double[] last = {Double.NaN};
		path.forEachStep(0.3, (back, from, fromHeight, to, toHeight, belowHeight) -> {
			assertEquals(fromHeight, toHeight, 1E-9, "level tread");
			assertTrue(from >= 0 && to <= 18 && to > from);
			assertTrue(fromHeight >= belowHeight - 1E-9, "rising");
			last[0] = to;
		});
		assertEquals(18, last[0], 1E-9, "steps up to the end");
	}

	@Test
	void anyDirectionAndDownhillMarked() {
		// Marked from the top down, 6 east and 8 south: turned round to rise, 10 long seen from above (1 in 2)
		final FreeEscalatorPath path = build(2, p(6, 5, 8), p(0, 0, 0));
		assertEquals(10, path.length, 1E-9);
		assertEquals(1 + D, path.height(0), 1E-9);
		assertEquals(0.6, path.directionX, 1E-9);
		assertEquals(0.8, path.directionZ, 1E-9);
		assertEquals(0, path.across(path.worldX(2, 0.7) , path.worldZ(2, 0.7)) - 0.7, 1E-9);
		assertEquals(2, path.along(path.worldX(2, 0.7), path.worldZ(2, 0.7)), 1E-9);
		assertFalse(path.cells().isEmpty());
	}

	@Test
	void whatIsNotAllowed() {
		assertEquals("too_few", FreeEscalatorPath.build(Arrays.asList(p(0, 0, 0)), 1).error);
		assertEquals("too_short", FreeEscalatorPath.build(Arrays.asList(p(0, 0, 0), p(0, 1, 0)), 1).error);
		// Off the vertical plane: a bend seen from above
		final FreeEscalatorPath.Result offPlane = FreeEscalatorPath.build(Arrays.asList(p(0, 0, 0), p(4, 0, 3), p(8, 0, 0)), 1);
		assertEquals("not_same_plane", offPlane.error);
		assertEquals(2, offPlane.arguments[0]);
		// Back along the line
		assertEquals("backwards", FreeEscalatorPath.build(Arrays.asList(p(0, 0, 0), p(6, 0, 0), p(4, 0, 0), p(10, 0, 0)), 1).error);
		// Only level, 1 in 2 and 1 in 1
		final FreeEscalatorPath.Result steep = FreeEscalatorPath.build(Arrays.asList(p(0, 0, 0), p(10, 7, 0)), 1);
		assertEquals("bad_slope", steep.error);
		assertEquals(35.0, steep.arguments[1]);
		assertEquals("bad_slope", FreeEscalatorPath.build(Arrays.asList(p(0, 0, 0), p(3, 4, 0)), 1).error, "53°");
		assertNull(FreeEscalatorPath.build(Arrays.asList(p(0, 0, 0), p(4, 4, 0)), 1).error, "45°");
		assertNull(FreeEscalatorPath.build(Arrays.asList(p(0, 0, 0), p(8, 4, 0)), 1).error, "26.57°");
		assertNull(FreeEscalatorPath.build(Arrays.asList(p(0, 0, 0), p(3, 0, 0), p(7, 4, 0), p(15, 8, 0), p(18, 8, 0)), 1).error, "level, 45°, 26.57°, level");
	}

	@Test
	void collisionFollowsTheTreads() {
		final FreeEscalatorPath path = build(1, p(0, 0, 0), p(3, 0, 0), p(7, 4, 0), p(15, 8, 0), p(18, 8, 0));
		final List<int[]> cells = path.cells();
		assertFalse(cells.isEmpty());
		// Every column over the run has its top at the standing height
		for (double s = 0.125; s < 18; s += 0.25) {
			final double x = path.worldX(s, 0), z = path.worldZ(s, 0);
			final int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
			final int column = (int) Math.floor((x - bx) * 4) * 4 + (int) Math.floor((z - bz) * 4);
			double top = Double.NaN;
			for (final int[] cell : cells) {
				if (cell[0] == bx && cell[2] == bz) {
					final byte height = path.columnHeights(cell[0], cell[1], cell[2])[column];
					if (height > 0 && !(top >= cell[1] + height / 16D)) {
						top = cell[1] + height / 16D;
					}
				}
			}
			assertFalse(Double.isNaN(top), "a surface at " + s);
			assertEquals(path.standHeight(path.along(bx + ((column / 4) + 0.5) / 4, bz + ((column % 4) + 0.5) / 4)), top, 1 / 16D + 1E-9, "at " + s);
		}
	}
}
