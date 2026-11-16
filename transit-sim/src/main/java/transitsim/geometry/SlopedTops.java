package transitsim.geometry;

/**
 * A block's top sloped to follow a rail's height (0.94.0), so the ground, a bridge's deck or a tunnel's floor under
 * track going up or down meets the track instead of standing in steps under it: the top's height at each of the block's
 * four corners, in sixteenths from its bottom, from 0 to 16, and between them the bilinear surface through those heights.
 * <p>
 * Corners in order: (x 0, z 0), (x 1, z 0), (x 1, z 1), (x 0, z 1), x and z as Minecraft has them within the block.
 */
public final class SlopedTops {

	public static final int CELLS = PlatformEdges.CELLS;
	/**
	 * A whole block's top: 16 at every corner
	 */
	public static final int FLAT = pack(CELLS, CELLS, CELLS, CELLS);
	/**
	 * No top at all: nothing of the block is under the rail
	 */
	public static final int NONE = 0;

	private SlopedTops() {
	}

	public static int pack(int corner00, int corner10, int corner11, int corner01) {
		return clamp(corner00) | clamp(corner10) << 5 | clamp(corner11) << 10 | clamp(corner01) << 15;
	}

	/**
	 * @return a corner's height in sixteenths, the corners numbered as in the class's note
	 */
	public static int corner(int packed, int index) {
		return packed >> (5 * index) & 31;
	}

	/**
	 * @param railHeights the rail's height over each corner, in blocks, as absolute heights
	 * @param blockY      the block's bottom
	 * @return the block's top following the rail, each corner rounded to sixteenths; {@link #FLAT} when the rail is over
	 * the whole block, {@link #NONE} when it is under all of it
	 */
	public static int of(double[] railHeights, int blockY) {
		final int[] corners = new int[4];
		for (int i = 0; i < 4; i++) {
			corners[i] = (int) Math.round((railHeights[i] - blockY) * CELLS);
		}
		return pack(corners[0], corners[1], corners[2], corners[3]);
	}

	public static boolean isFlat(int packed) {
		return packed == FLAT;
	}

	public static boolean isNone(int packed) {
		return packed == NONE;
	}

	/**
	 * @return the top's height over a point of the block, in blocks, x and z from 0 to 1
	 */
	public static double heightAt(int packed, double x, double z) {
		final double h00 = corner(packed, 0);
		final double h10 = corner(packed, 1);
		final double h11 = corner(packed, 2);
		final double h01 = corner(packed, 3);
		final double south = h00 + (h10 - h00) * x;
		final double north = h01 + (h11 - h01) * x;
		return (south + (north - south) * z) / CELLS;
	}

	/**
	 * @return the height of each of the block's 16 by 16 columns, in sixteenths, as [x * 16 + z]: the top over the
	 * column's centre, rounded
	 */
	public static int[] columnHeights(int packed) {
		final int[] heights = new int[CELLS * CELLS];
		for (int x = 0; x < CELLS; x++) {
			for (int z = 0; z < CELLS; z++) {
				heights[x * CELLS + z] = (int) Math.round(heightAt(packed, (x + 0.5) / CELLS, (z + 0.5) / CELLS) * CELLS);
			}
		}
		return heights;
	}

	private static int clamp(int sixteenths) {
		return Math.max(0, Math.min(CELLS, sixteenths));
	}
}
