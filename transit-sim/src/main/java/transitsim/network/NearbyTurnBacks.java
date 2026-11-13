package transitsim.network;

import java.util.*;
import java.util.function.LongToIntFunction;
import java.util.function.ToLongBiFunction;

/**
 * Where trains turn back near a platform when a route lists nowhere (moved out of the game's depot path building,
 * 2026-11-13, step 2a of splitting the depot): the dead ends of the turn back rails within reach, the nearest first, and
 * after them one node of each stabling block within reach (运用补完 step 4). Nodes are block positions packed as Minecraft
 * packs them ({@link #x}, {@link #y}, {@link #z}); distances are squared, from the platform's middle.
 * <p>
 * The turn back rails are listed once for the rails searched, and each platform is looked up once (optimisation round 5,
 * step 3: reading all the rails of the world each time ran about 13 000 times on a large network).
 */
public final class NearbyTurnBacks {

	/**
	 * Each turn back rail as its two nodes, in the order the host listed them.
	 */
	private final List<long[]> turnBackRails;
	/**
	 * How many rails leave a node, 0 for none: 1 is a dead end.
	 */
	private final LongToIntFunction degree;
	/**
	 * The stabling block a rail between two nodes is in, or null.
	 */
	private final ToLongBiFunction<Long, Long> stablingBlockOf;
	private final Map<Long, Result> results = new HashMap<>();

	public static final long NO_BLOCK = Long.MIN_VALUE;

	/**
	 * @param stablingBlockOf the stabling block of the rail between two nodes, or {@link #NO_BLOCK}
	 */
	public NearbyTurnBacks(List<long[]> turnBackRails, LongToIntFunction degree, ToLongBiFunction<Long, Long> stablingBlockOf) {
		this.turnBackRails = turnBackRails;
		this.degree = degree;
		this.stablingBlockOf = stablingBlockOf;
	}

	/**
	 * What was found for one platform: the turn back nodes and, after them, the stabling blocks' nodes ({@link #nodes}), and
	 * the stabling blocks' nodes alone ({@link #stabling}).
	 */
	public static final class Result {

		public final List<Long> nodes;
		public final List<Long> stabling;

		private Result(List<Long> nodes, List<Long> stabling) {
			this.nodes = Collections.unmodifiableList(nodes);
			this.stabling = Collections.unmodifiableList(stabling);
		}
	}

	/**
	 * @param platformId the platform, which the result is kept by
	 * @param center     the platform's middle
	 * @param reach      how far a turn back rail may be, in blocks, by its nearer end
	 * @param max        how many dead ends at most, and how many stabling blocks' nodes at most
	 */
	public synchronized Result near(long platformId, long center, int reach, int max) {
		return results.computeIfAbsent(platformId, id -> find(center, reach, max));
	}

	private Result find(long center, int reach, int max) {
		final Set<Long> ends = new HashSet<>();
		// The stabling blocks within reach: each one way round, by the turn back rails in it
		final Map<Long, List<long[]>> stablingBlocks = new TreeMap<>();
		for (final long[] turnBackRail : turnBackRails) {
			if (Math.min(distSqr(turnBackRail[0], center), distSqr(turnBackRail[1], center)) <= (double) reach * reach) {
				final long block = stablingBlockOf.applyAsLong(turnBackRail[0], turnBackRail[1]);
				if (block != NO_BLOCK) {
					stablingBlocks.computeIfAbsent(block, key -> new ArrayList<>()).add(turnBackRail);
					continue;
				}
				for (final long pos : turnBackRail) {
					if (degree.applyAsInt(pos) == 1) {
						ends.add(pos);
					}
				}
			}
		}
		final Comparator<Long> nearestFirst = Comparator.comparingDouble((Long pos) -> distSqr(pos, center)).thenComparingLong(Long::longValue);
		final List<Long> sorted = new ArrayList<>(ends);
		sorted.sort(nearestFirst);
		final List<Long> nodes = new ArrayList<>(sorted.size() > max ? sorted.subList(0, max) : sorted);
		// After the turn back rails, each stabling block's node, the nearest block first, as many again at most
		final List<Long> stablingNodes = new ArrayList<>();
		stablingBlocks.values().forEach(blockRails -> {
			final long node = stablingNode(blockRails, center, degree);
			if (!nodes.contains(node) && !stablingNodes.contains(node)) {
				stablingNodes.add(node);
			}
		});
		stablingNodes.sort(nearestFirst);
		final List<Long> stablingKept = new ArrayList<>(stablingNodes.size() > max ? stablingNodes.subList(0, max) : stablingNodes);
		nodes.addAll(stablingKept);
		return new Result(nodes, stablingKept);
	}

	/**
	 * @return where a train turns back in a stabling block: a dead end of a turn back rail in it if there is one, the
	 * nearest; else the end of its turn back rails furthest from the platform, as the train runs in from the platform
	 */
	static long stablingNode(List<long[]> blockRails, long center, LongToIntFunction degree) {
		Long deadEnd = null;
		Long furthest = null;
		for (final long[] turnBackRail : blockRails) {
			for (final long pos : turnBackRail) {
				if (degree.applyAsInt(pos) == 1 && (deadEnd == null || isBefore(pos, deadEnd, center, false))) {
					deadEnd = pos;
				}
				if (furthest == null || isBefore(pos, furthest, center, true)) {
					furthest = pos;
				}
			}
		}
		return deadEnd == null ? furthest : deadEnd;
	}

	/**
	 * @return whether a node comes before another: nearer the middle (or further, with {@code furthestFirst}), by packed
	 * position when as far
	 */
	static boolean isBefore(long pos, long other, long center, boolean furthestFirst) {
		final double distance = distSqr(pos, center);
		final double otherDistance = distSqr(other, center);
		return distance == otherDistance ? pos < other : furthestFirst == distance > otherDistance;
	}

	/**
	 * @return the squared distance between two packed positions, by their whole block coordinates (as Minecraft 1.20's
	 * {@code BlockPos.distSqr})
	 */
	public static double distSqr(long pos, long other) {
		final double dx = x(pos) - x(other);
		final double dy = y(pos) - y(other);
		final double dz = z(pos) - z(other);
		return dx * dx + dy * dy + dz * dz;
	}

	public static int x(long packed) {
		return (int) (packed >> 38);
	}

	public static int y(long packed) {
		return (int) (packed << 52 >> 52);
	}

	public static int z(long packed) {
		return (int) (packed << 26 >> 38);
	}

	public static long pack(int x, int y, int z) {
		return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (long) y & 0xFFFL;
	}
}
