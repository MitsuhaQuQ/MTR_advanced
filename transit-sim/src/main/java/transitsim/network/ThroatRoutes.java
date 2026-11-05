package transitsim.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongFunction;
import java.util.function.LongToIntFunction;
import java.util.function.ToLongFunction;

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
		return of(nodes, position, stopRails, rails, rail -> 0);
	}

	/**
	 * @param zones the interlocking zone each rail is in, by its key ({@link RailKeys}), or 0 for none: a signal block of
	 *              the interlocking colour, which a route through the throat takes as a whole
	 */
	public static List<StopThroat> of(long[] nodes, LongFunction<double[]> position, int[] stopRails, LongToIntFunction rails, ToLongFunction<UUID> zones) {
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
			final Map<Long, int[]> entryZones = new LinkedHashMap<>();
			double plain = 0;
			for (int j = platform; j >= 1 && j > (previous < 0 ? 0 : previous + 1); j--) {
				if (rails.applyAsInt(nodes[j]) >= 3) {
					entry.add(new Item(nodes[j], nodes[j - 1], nodes[j + 1], j));
					plain = 0;
				}
				// The rail before this node, from j - 1 to j
				if (addZone(entryZones, zones.applyAsLong(RailKeys.of(nodes[j - 1], nodes[j])), j - 1)) {
					plain = 0;
				}
				plain += distance(position, nodes[j], nodes[j - 1]);
				if (plain > PLAIN_DISTANCE) {
					break;
				}
			}
			entryZones.forEach((zone, span) -> entry.add(Item.zone(zone, span[0], span[1], nodes[span[0]])));
			// Going out: on from the platform's last node to just before the next stop's platform
			final int next = nextRail(stopRails, stop);
			final List<Item> exit = new ArrayList<>();
			final Map<Long, int[]> exitZones = new LinkedHashMap<>();
			plain = 0;
			for (int j = platform + 1; j + 1 < nodes.length && (next < 0 || j < next); j++) {
				if (rails.applyAsInt(nodes[j]) >= 3) {
					exit.add(new Item(nodes[j], nodes[j - 1], nodes[j + 1], j));
					plain = 0;
				}
				// The rail after this node, from j to j + 1
				if (addZone(exitZones, zones.applyAsLong(RailKeys.of(nodes[j], nodes[j + 1])), j)) {
					plain = 0;
				}
				plain += distance(position, nodes[j], nodes[j + 1]);
				if (plain > PLAIN_DISTANCE) {
					break;
				}
			}
			exitZones.forEach((zone, span) -> exit.add(Item.zone(zone, span[0], span[1], nodes[span[0]])));
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

	/**
	 * Widens the span of rails of the path a zone covers by this rail.
	 *
	 * @return whether the rail is in a zone
	 */
	private static boolean addZone(Map<Long, int[]> zones, long zone, int rail) {
		if (zone == 0) {
			return false;
		}
		final int[] span = zones.computeIfAbsent(zone, key -> new int[]{rail, rail});
		span[0] = Math.min(span[0], rail);
		span[1] = Math.max(span[1], rail);
		return true;
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
	 * A turnout and the way through it, by the two nodes on either side, in either order; or an interlocking zone, taken as
	 * a whole whichever way.
	 */
	public static final class Item {

		/**
		 * Both sides of a zone, which no node has.
		 */
		private static final long ZONE_SIDE = Long.MIN_VALUE;

		/**
		 * The turnout's node, or the zone's ID.
		 */
		public final long node;
		public final long side1;
		public final long side2;
		/**
		 * Where on the path the turnout is, as the index of the node, or -1; not part of what the item is. For a zone, the
		 * first node of its first rail on the path.
		 */
		public final int index;
		/**
		 * For a zone, the index of its last rail on the path, which the train's tail has to leave; for a turnout, the same
		 * as {@link #index}.
		 */
		public final int endIndex;
		private long place;

		/**
		 * @param firstRail the first rail of the path in the zone
		 * @param lastRail  the last rail of the path in the zone
		 * @param firstNode the node where the path enters it
		 */
		public static Item zone(long zone, int firstRail, int lastRail, long firstNode) {
			final Item item = new Item(zone, ZONE_SIDE, ZONE_SIDE, firstRail, lastRail + 1);
			item.place = firstNode;
			return item;
		}

		/**
		 * Where to show it: the turnout's node, or the node where the path enters the zone.
		 */
		public long getPlace() {
			return isZone() ? place : node;
		}

		public Item(long node, long neighbour1, long neighbour2) {
			this(node, neighbour1, neighbour2, -1);
		}

		public Item(long node, long neighbour1, long neighbour2, int index) {
			this(node, neighbour1, neighbour2, index, index);
		}

		private Item(long node, long neighbour1, long neighbour2, int index, int endIndex) {
			this.node = node;
			side1 = Math.min(neighbour1, neighbour2);
			side2 = Math.max(neighbour1, neighbour2);
			this.index = index;
			this.endIndex = endIndex;
		}

		public boolean isZone() {
			return side1 == ZONE_SIDE;
		}

		/**
		 * A turnout conflicts with another way through it; a zone with any other use of it, whichever way.
		 */
		public boolean conflicts(Item other) {
			if (isZone() || other.isZone()) {
				return isZone() && other.isZone() && node == other.node;
			}
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
