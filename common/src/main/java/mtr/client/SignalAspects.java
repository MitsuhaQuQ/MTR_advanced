package mtr.client;

import mtr.data.Rail;
import mtr.path.PathData;
import net.minecraft.core.BlockPos;
import transitsim.network.BlockSections;

import java.util.*;

/**
 * What a signal shows, counted in block sections ahead (decoupling document 5.4, 2b): 1 when the first section past the
 * signal is taken, 2 when the second is, and so on up to the signal's own number of aspects, 0 when they are all clear,
 * or -1 when no rail leaves the signal's node that way.
 * <p>
 * It goes along the rails the client has, the way the signal faces, cutting them into block sections as the server does:
 * a long rail into pieces of the braking distance at its speed limit, and a signal block into the sections the server
 * cuts it into. Past a turnout every way counts, so the most restrictive one shows. A rail on from a node that the client
 * knows of only from the map, whose length it doesn't have, counts as taken whichever way it goes, so a signal never
 * shows clear for track it can't see.
 */
public final class SignalAspects {

	/**
	 * How many rails it goes along at most, against loops.
	 */
	private static final int MAX_RAILS = 256;
	private static int mapRailsSize = -1;
	private static Map<Long, Set<Long>> mapNeighbours = Collections.emptyMap();

	private SignalAspects() {
	}

	/**
	 * @param facing  the way the signal faces, in degrees
	 * @param aspects how many aspects the signal has, counting clear
	 */
	public static int getAspect(BlockPos startPos, float facing, int aspects) {
		ClientData.SIGNAL_BLOCKS.updateSections(ClientData.interlockColor, CLIENT_RAILS, ClientData.RAILS.size());
		final BlockSections sections = ClientData.SIGNAL_BLOCKS.getBlockSections();
		final Deque<Step> steps = new ArrayDeque<>();
		steps.add(new Step(startPos, facing, 0, null));
		int taken = Integer.MAX_VALUE;
		boolean anyRail = false;
		int railsGone = 0;
		while (!steps.isEmpty() && railsGone < MAX_RAILS) {
			final Step step = steps.pop();
			final Map<BlockPos, Rail> railMap = ClientData.RAILS.getOrDefault(step.node, Collections.emptyMap());
			// Track on from here the client only knows from the map: not known, so taken
			if (step.entered > 0 && step.entered + 1 < aspects && hasUnknownRail(step.node, railMap)) {
				taken = Math.min(taken, step.entered + 1);
			}
			// A station throat turnout facing this way (0.99.0): stop until a route through it is locked for a train, and then
			// only the way locked counts
			final List<long[]> locked = getLockedWays(step.node, railMap, step.facing);
			if (locked != null && locked.isEmpty()) {
				if (!railMap.isEmpty()) {
					anyRail = true;
				}
				taken = Math.min(taken, step.entered + 1);
				continue;
			}
			for (final Map.Entry<BlockPos, Rail> entry : railMap.entrySet()) {
				final Rail rail = entry.getValue();
				if (!rail.facingStart.similarFacing(step.facing)) {
					continue;
				}
				if (locked != null && locked.stream().noneMatch(sides -> sides[0] == entry.getKey().asLong() || sides[1] == entry.getKey().asLong())) {
					continue;
				}
				anyRail = true;
				railsGone++;
				final BlockPos endPos = entry.getKey();
				final UUID railKey = PathData.getRailProduct(step.node, endPos);
				final boolean forwards = step.node.asLong() < endPos.asLong();
				int entered = step.entered;
				UUID lastSection = step.lastSection;
				boolean stop = false;
				for (final int piece : getPieceOrder(rail, forwards)) {
					final UUID unit = BlockSections.getPieceKey(railKey, piece);
					final List<UUID> section = sections == null ? null : sections.getSection(unit);
					final UUID sectionId = section == null || section.isEmpty() ? unit : section.get(0);
					final int newEntered = sectionId.equals(lastSection) ? entered : entered + 1;
					if (newEntered >= aspects) {
						stop = true;
						break;
					}
					if (isTaken(railKey, piece)) {
						taken = Math.min(taken, newEntered);
						stop = true;
						break;
					}
					entered = newEntered;
					lastSection = sectionId;
				}
				if (!stop) {
					steps.push(new Step(endPos, rail.facingEnd.getOpposite().angleDegrees, entered, lastSection));
				}
			}
		}
		return !anyRail ? -1 : taken == Integer.MAX_VALUE ? 0 : taken;
	}

	/**
	 * @return for a station throat turnout facing this way, the ways locked through it now (empty when none is), or null
	 * when the node is no such turnout or the server doesn't say (0.99.0)
	 */
	private static List<long[]> getLockedWays(BlockPos node, Map<BlockPos, Rail> railMap, float facing) {
		final Set<Long> turnouts = ClientData.THROAT_TURNOUTS;
		if (turnouts == null || !turnouts.contains(node.asLong())) {
			return null;
		}
		int ways = 0;
		for (final Rail rail : railMap.values()) {
			if (rail.facingStart.similarFacing(facing)) {
				ways++;
			}
		}
		if (ways < 2) {
			return null;
		}
		return ClientData.LOCKED_TURNOUTS.getOrDefault(node.asLong(), Collections.emptyList());
	}

	/**
	 * The rails of the first block section past a signal's node, as lines to draw {x1, y1, z1, x2, y2, z2} (0.99.0, the
	 * section a signal protects highlighted while looked at); every way past a turnout
	 */
	public static List<double[]> getFirstSection(BlockPos startPos, float facing) {
		ClientData.SIGNAL_BLOCKS.updateSections(ClientData.interlockColor, CLIENT_RAILS, ClientData.RAILS.size());
		final BlockSections sections = ClientData.SIGNAL_BLOCKS.getBlockSections();
		final List<double[]> lines = new ArrayList<>();
		final Deque<Step> steps = new ArrayDeque<>();
		steps.add(new Step(startPos, facing, 0, null));
		int railsGone = 0;
		while (!steps.isEmpty() && railsGone < MAX_RAILS) {
			final Step step = steps.pop();
			for (final Map.Entry<BlockPos, Rail> entry : ClientData.RAILS.getOrDefault(step.node, Collections.emptyMap()).entrySet()) {
				final Rail rail = entry.getValue();
				if (!rail.facingStart.similarFacing(step.facing)) {
					continue;
				}
				railsGone++;
				final BlockPos endPos = entry.getKey();
				final UUID railKey = PathData.getRailProduct(step.node, endPos);
				final int[] order = getPieceOrder(rail, step.node.asLong() < endPos.asLong());
				int entered = step.entered;
				UUID lastSection = step.lastSection;
				boolean stop = false;
				for (int i = 0; i < order.length; i++) {
					final UUID unit = BlockSections.getPieceKey(railKey, order[i]);
					final List<UUID> section = sections == null ? null : sections.getSection(unit);
					final UUID sectionId = section == null || section.isEmpty() ? unit : section.get(0);
					entered = sectionId.equals(lastSection) ? entered : entered + 1;
					if (entered > 1) {
						stop = true;
						break;
					}
					lastSection = sectionId;
					final double length = rail.getLength();
					final int samples = Math.max(1, (int) Math.ceil(length / order.length / 2));
					for (int k = 0; k < samples; k++) {
						final net.minecraft.world.phys.Vec3 from = rail.getPosition(length * (i + (double) k / samples) / order.length);
						final net.minecraft.world.phys.Vec3 to = rail.getPosition(length * (i + (double) (k + 1) / samples) / order.length);
						lines.add(new double[]{from.x, from.y, from.z, to.x, to.y, to.z});
					}
				}
				if (!stop) {
					steps.push(new Step(endPos, rail.facingEnd.getOpposite().angleDegrees, entered, lastSection));
				}
			}
		}
		return lines;
	}

	/**
	 * @return whether a piece of a rail is taken, as the server last said; from a server that doesn't send pieces, whether
	 * the rail or a signal block on it is
	 */
	public static boolean isTaken(UUID railKey, int piece) {
		final Map<UUID, Set<Integer>> sections = ClientData.OCCUPIED_SECTIONS;
		if (sections != null) {
			final Set<Integer> pieces = sections.get(railKey);
			return pieces != null && pieces.contains(piece);
		}
		return ClientData.SIGNAL_BLOCKS.isOccupied(railKey) || Boolean.TRUE.equals(ClientData.OCCUPIED_RAILS.get(railKey));
	}

	/**
	 * @return the pieces of a rail in the order a train going this way reaches them
	 */
	public static int[] getPieceOrder(Rail rail, boolean forwards) {
		final int count = BlockSections.getPieceCount(rail.getLength(), mtr.data.TrainServer.cutSpeed(rail.railType));
		final int[] order = new int[count];
		for (int k = 0; k < count; k++) {
			order[k] = forwards ? k : count - 1 - k;
		}
		return order;
	}

	/**
	 * @return whether the map has a rail at this node that the client doesn't have, whichever way it goes
	 */
	private static boolean hasUnknownRail(BlockPos node, Map<BlockPos, Rail> railMap) {
		if (mapRailsSize != ClientData.MAP_RAILS.size()) {
			final Map<Long, Set<Long>> neighbours = new HashMap<>();
			ClientData.MAP_RAILS.forEach(mapRail -> {
				neighbours.computeIfAbsent(mapRail.node1, key -> new HashSet<>()).add(mapRail.node2);
				neighbours.computeIfAbsent(mapRail.node2, key -> new HashSet<>()).add(mapRail.node1);
			});
			mapNeighbours = neighbours;
			mapRailsSize = ClientData.MAP_RAILS.size();
		}
		for (final long other : mapNeighbours.getOrDefault(node.asLong(), Collections.emptySet())) {
			if (!railMap.containsKey(BlockPos.of(other))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The lengths and speed limits of the rails the client has, for cutting signal blocks as the server does.
	 */
	private static final BlockSections.RailInfo CLIENT_RAILS = new BlockSections.RailInfo() {
		@Override
		public double getLength(UUID rail) {
			final Rail found = find(rail);
			return found == null ? 0 : found.getLength();
		}

		@Override
		public float getMaxSpeed(UUID rail) {
			final Rail found = find(rail);
			return found == null ? 0 : mtr.data.TrainServer.cutSpeed(found.railType);
		}

		private Rail find(UUID rail) {
			final Map<BlockPos, Rail> connections = ClientData.RAILS.get(BlockPos.of(rail.getMostSignificantBits()));
			return connections == null ? null : connections.get(BlockPos.of(rail.getLeastSignificantBits()));
		}
	};

	private static final class Step {

		private final BlockPos node;
		private final float facing;
		/**
		 * How many sections the way here has entered.
		 */
		private final int entered;
		private final UUID lastSection;

		private Step(BlockPos node, float facing, int entered, UUID lastSection) {
			this.node = node;
			this.facing = facing;
			this.entered = entered;
			this.lastSection = lastSection;
		}
	}
}
