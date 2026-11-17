package mtr.data;

import io.netty.buffer.Unpooled;
import mtr.block.BlockNode;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * An edit of nodes that reshapes their rails (angle, grade, transition curves; 0.95.0), checked before anyone is sent the
 * new rails. Rails of platforms may change, as platforms may be curved, but not get steeper than
 * {@link #MAX_PLATFORM_GRADE} anywhere or go up and down more than {@link #MAX_SAVED_RAIL_RISE} along them; rails of
 * sidings not steeper than {@link #MAX_SIDING_GRADE}, the same height rule, and not change their length, as the trains
 * kept there are made for it. A rail that already broke a rule may still change as long as it doesn't get worse. If a
 * check fails, {@link #undo} puts the rails and the nodes back as they were.
 * <p>
 * The edit covers the nodes given and the nodes at the other ends of their rails, whose grades follow them.
 */
public final class RailEdit {

	public static final double MAX_PLATFORM_GRADE = 0.003;
	public static final double MAX_SIDING_GRADE = 0.0015;
	public static final double MAX_SAVED_RAIL_RISE = 0.5;

	private static final double SAMPLE_STEP = 0.25;
	private static final double TOLERANCE = 1E-6;
	private static final double LENGTH_TOLERANCE = 1E-3;
	private static final double PER_MILLE = 1000;

	private final Level world;
	private final Map<BlockPos, Map<BlockPos, Rail>> rails;
	private final Map<BlockPos, CompoundTag> nodeSettings = new HashMap<>();
	private final Map<BlockPos, Map<BlockPos, Rail>> oldRails = new HashMap<>();

	public RailEdit(Level world, Map<BlockPos, Map<BlockPos, Rail>> rails, Collection<BlockPos> nodes) {
		this.world = world;
		this.rails = rails;
		final Set<BlockPos> covered = new HashSet<>(nodes);
		nodes.forEach(pos -> covered.addAll(rails.getOrDefault(pos, Collections.emptyMap()).keySet()));
		for (final BlockPos pos : covered) {
			nodeSettings.put(pos, BlockNode.saveSettings(world, pos));
			rails.getOrDefault(pos, Collections.emptyMap()).forEach((posEnd, rail) -> {
				oldRails.computeIfAbsent(pos, key -> new HashMap<>()).put(posEnd, copy(rail));
				final Rail backwardsRail = DataCache.tryGet(rails, posEnd, pos);
				if (backwardsRail != null) {
					oldRails.computeIfAbsent(posEnd, key -> new HashMap<>()).put(pos, copy(backwardsRail));
				}
			});
		}
	}

	/**
	 * @return the translation key and arguments of the first rule broken, or null if none is
	 */
	public Object[] check() {
		for (final Map.Entry<BlockPos, Map<BlockPos, Rail>> entry : oldRails.entrySet()) {
			for (final Map.Entry<BlockPos, Rail> oldEntry : entry.getValue().entrySet()) {
				final Rail newRail = DataCache.tryGet(rails, entry.getKey(), oldEntry.getKey());
				if (newRail == null) {
					continue;
				}
				final Object[] error = checkRail(oldEntry.getValue(), newRail, entry.getKey());
				if (error != null) {
					return error;
				}
			}
		}
		return null;
	}

	/**
	 * The rules for a new rail of a platform or siding, which has nothing to be compared with.
	 *
	 * @return the translation key and arguments of the first rule broken, or null if none is
	 */
	public static Object[] checkNewRail(Rail rail, BlockPos pos) {
		return checkRail(null, rail, pos);
	}

	public void undo() {
		oldRails.forEach((pos, connections) -> connections.forEach((posEnd, rail) -> {
			final Map<BlockPos, Rail> current = rails.get(pos);
			if (current != null && current.containsKey(posEnd)) {
				current.put(posEnd, rail);
			}
		}));
		nodeSettings.forEach((pos, compoundTag) -> BlockNode.restoreSettings(world, pos, compoundTag));
	}

	/**
	 * Saves and sends the rails that the edit changed, each pair once.
	 */
	public void sendChanged(RailwayData railwayData) {
		final Set<Long> done = new HashSet<>();
		oldRails.forEach((pos, connections) -> connections.forEach((posEnd, oldRail) -> {
			final Rail rail = DataCache.tryGet(rails, pos, posEnd);
			final Rail backwardsRail = DataCache.tryGet(rails, posEnd, pos);
			if (rail == null || backwardsRail == null || !done.add(Math.min(pos.asLong(), posEnd.asLong()) * 31 + Math.max(pos.asLong(), posEnd.asLong()))) {
				return;
			}
			final Rail oldBackwardsRail = oldRails.getOrDefault(posEnd, Collections.emptyMap()).get(pos);
			if (!sameData(oldRail, rail) || oldBackwardsRail == null || !sameData(oldBackwardsRail, backwardsRail)) {
				railwayData.sendRailChanged(pos, posEnd, rail, backwardsRail);
			}
		}));
	}

	private static Object[] checkRail(Rail oldRail, Rail newRail, BlockPos pos) {
		final boolean platform = newRail.railType == RailType.PLATFORM;
		final boolean siding = newRail.railType == RailType.SIDING;
		if (!platform && !siding) {
			return null;
		}
		final double[] oldSlope = oldRail == null ? new double[]{0, 0} : slope(oldRail);
		final double[] newSlope = slope(newRail);
		final double maxGrade = platform ? MAX_PLATFORM_GRADE : MAX_SIDING_GRADE;
		final String position = String.format(Locale.ENGLISH, "%d, %d, %d", pos.getX(), pos.getY(), pos.getZ());
		if (newSlope[0] > maxGrade + TOLERANCE && newSlope[0] > oldSlope[0] + TOLERANCE) {
			return new Object[]{platform ? "gui.mtr.platform_too_steep" : "gui.mtr.siding_too_steep", position, String.format(Locale.ENGLISH, "%.1f", newSlope[0] * PER_MILLE), String.format(Locale.ENGLISH, "%.1f", maxGrade * PER_MILLE)};
		}
		if (newSlope[1] > MAX_SAVED_RAIL_RISE + TOLERANCE && newSlope[1] > oldSlope[1] + TOLERANCE) {
			return new Object[]{"gui.mtr.saved_rail_too_uneven", position, String.format(Locale.ENGLISH, "%.2f", newSlope[1])};
		}
		if (siding && oldRail != null && Math.abs(oldRail.getLength() - newRail.getLength()) > LENGTH_TOLERANCE) {
			return new Object[]{"gui.mtr.siding_length_changes", position};
		}
		return null;
	}

	/**
	 * @return {the steepest grade, how far the rail goes up and down along it}
	 */
	public static double[] slope(Rail rail) {
		final double length = rail.getLength();
		final int steps = Math.max(1, (int) Math.ceil(length / SAMPLE_STEP));
		double previous = rail.getPosition(0).y;
		double min = previous;
		double max = previous;
		// The ends exactly, as sampling a vertical curve just misses its steepest part
		double steepest = Math.max(Math.abs(rail.getGradeStart()), Math.abs(rail.getGradeEnd()));
		for (int i = 1; i <= steps; i++) {
			final double y = rail.getPosition(length * i / steps).y;
			steepest = Math.max(steepest, Math.abs(y - previous) / (length / steps));
			min = Math.min(min, y);
			max = Math.max(max, y);
			previous = y;
		}
		return new double[]{steepest, max - min};
	}

	private static Rail copy(Rail rail) {
		return new Rail(write(rail));
	}

	private static boolean sameData(Rail rail1, Rail rail2) {
		return write(rail1).equals(write(rail2));
	}

	private static FriendlyByteBuf write(Rail rail) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		rail.writePacket(packet);
		return packet;
	}
}
