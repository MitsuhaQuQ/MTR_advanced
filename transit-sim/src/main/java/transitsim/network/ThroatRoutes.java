package transitsim.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.LongFunction;
import java.util.function.LongToIntFunction;

/**
 * The routes a train takes through the throats of the stations it stops at (decoupling document 5.4, L2): the turnouts it
 * passes coming in to each platform and going out of it, and which way through each. Two trains using a turnout by
 * different ways can't both be on it, so their routes conflict; trains going the same way through it follow each other,
 * which signal blocks keep apart, and the same way in opposite directions is head-on running, which single track
 * reservations deal with.
 * <p>
 * A turnout is a node where three or more rails meet, as {@link NetworkBuilder} has it. Rails crossing with no node
 * aren't known. A throat ends where the path has run {@link #PLAIN_DISTANCE} blocks without a turnout, or at the
 * neighbouring stop.
 */
public final class ThroatRoutes {

	public static final double PLAIN_DISTANCE = 64;

	private ThroatRoutes() {
	}

	/**
	 * @param nodes     the route's path as the nodes it passes, rail {@code i} running from node {@code i} to {@code i + 1}
	 * @param position  each node's x and z
	 * @param stopRails for each stop, the rail of the path that is its platform, or -1 if the path doesn't reach it
	 * @param rails     how many different rails meet at a node
	 * @return the entry and exit route of each stop
	 */
	public static List<StopThroat> of(long[] nodes, LongFunction<double[]> position, int[] stopRails, LongToIntFunction rails) {
		final List<StopThroat> throats = new ArrayList<>();
		for (int stop = 0; stop < stopRails.length; stop++) {
			final int platform = stopRails[stop];
			if (platform < 0 || platform + 1 >= nodes.length) {
				throats.add(new StopThroat(Collections.emptyList(), Collections.emptyList()));
				continue;
			}
			// Coming in: back from the platform's first node to just after the previous stop's platform
			final int previous = previousRail(stopRails, stop);
			final List<Item> entry = new ArrayList<>();
			double plain = 0;
			for (int j = platform; j >= 1 && j > (previous < 0 ? 0 : previous + 1); j--) {
				if (rails.applyAsInt(nodes[j]) >= 3) {
					entry.add(new Item(nodes[j], nodes[j - 1], nodes[j + 1]));
					plain = 0;
				}
				plain += distance(position, nodes[j], nodes[j - 1]);
				if (plain > PLAIN_DISTANCE) {
					break;
				}
			}
			// Going out: on from the platform's last node to just before the next stop's platform
			final int next = nextRail(stopRails, stop);
			final List<Item> exit = new ArrayList<>();
			plain = 0;
			for (int j = platform + 1; j + 1 < nodes.length && (next < 0 || j < next); j++) {
				if (rails.applyAsInt(nodes[j]) >= 3) {
					exit.add(new Item(nodes[j], nodes[j - 1], nodes[j + 1]));
					plain = 0;
				}
				plain += distance(position, nodes[j], nodes[j + 1]);
				if (plain > PLAIN_DISTANCE) {
					break;
				}
			}
			throats.add(new StopThroat(entry, exit));
		}
		return throats;
	}

	/**
	 * @return whether two routes use a turnout by different ways
	 */
	public static Item findConflict(List<Item> route1, List<Item> route2) {
		for (final Item item1 : route1) {
			for (final Item item2 : route2) {
				if (item1.conflicts(item2)) {
					return item1;
				}
			}
		}
		return null;
	}

	private static int previousRail(int[] stopRails, int stop) {
		for (int i = stop - 1; i >= 0; i--) {
			if (stopRails[i] >= 0) {
				return stopRails[i];
			}
		}
		return -1;
	}

	private static int nextRail(int[] stopRails, int stop) {
		for (int i = stop + 1; i < stopRails.length; i++) {
			if (stopRails[i] >= 0) {
				return stopRails[i];
			}
		}
		return -1;
	}

	private static double distance(LongFunction<double[]> position, long node1, long node2) {
		final double[] position1 = position.apply(node1);
		final double[] position2 = position.apply(node2);
		return Math.hypot(position2[0] - position1[0], position2[1] - position1[1]);
	}

	/**
	 * A turnout and the way through it, by the two nodes on either side, in either order.
	 */
	public static final class Item {

		public final long node;
		public final long side1;
		public final long side2;

		public Item(long node, long neighbour1, long neighbour2) {
			this.node = node;
			side1 = Math.min(neighbour1, neighbour2);
			side2 = Math.max(neighbour1, neighbour2);
		}

		public boolean conflicts(Item other) {
			return node == other.node && (side1 != other.side1 || side2 != other.side2);
		}

		@Override
		public boolean equals(Object object) {
			return object instanceof Item && ((Item) object).node == node && ((Item) object).side1 == side1 && ((Item) object).side2 == side2;
		}

		@Override
		public int hashCode() {
			return Objects.hash(node, side1, side2);
		}
	}

	public static final class StopThroat {

		/**
		 * The turnouts passed coming in, from the platform back.
		 */
		public final List<Item> entry;
		/**
		 * The turnouts passed going out, from the platform on.
		 */
		public final List<Item> exit;

		public StopThroat(List<Item> entry, List<Item> exit) {
			this.entry = entry;
			this.exit = exit;
		}
	}
}
