package mtr.advanced;

import mtr.data.Rail;
import mtr.data.TransportMode;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * The rails in a box around a station, as their real shape, for the track diagram of the advanced editor (design document
 * 6.12, block D). Clients only keep the full rails near the player, and the dashboard map's rail lines are straight between
 * their ends, which draws curved crossovers wrong, so the server samples each rail along its curve.
 */
public final class StationLayouts {

	/**
	 * The widest box asked for, in blocks.
	 */
	public static final int MAX_SIZE = 1024;
	private static final int MAX_RAILS = 4000;
	private static final int MAX_POINTS = 48;
	private static final double POINT_SPACING = 3;
	private static final long MIN_REQUEST_INTERVAL_MILLIS = 150;
	private static final Map<UUID, Long> LAST_REQUESTS = new HashMap<>();

	private StationLayouts() {
	}

	/**
	 * @return false if the player asked too soon after the last time
	 */
	public static boolean allowRequest(UUID player) {
		final long millis = System.currentTimeMillis();
		synchronized (LAST_REQUESTS) {
			if (millis - LAST_REQUESTS.getOrDefault(player, 0L) < MIN_REQUEST_INTERVAL_MILLIS) {
				return false;
			}
			LAST_REQUESTS.put(player, millis);
			return true;
		}
	}

	/**
	 * Writes the box, then each rail of the transport mode reaching into it as a line of points along its curve, each
	 * rail once for both directions.
	 */
	public static void write(Map<BlockPos, Map<BlockPos, Rail>> rails, int[] box, TransportMode transportMode, FriendlyByteBuf packet) {
		final List<float[]> lines = new ArrayList<>();
		boolean truncated = false;
		for (final Map.Entry<BlockPos, Map<BlockPos, Rail>> entry : rails.entrySet()) {
			final BlockPos start = entry.getKey();
			for (final Map.Entry<BlockPos, Rail> railEntry : entry.getValue().entrySet()) {
				final Rail rail = railEntry.getValue();
				final BlockPos end = railEntry.getKey();
				if (start.asLong() > end.asLong() || rail.transportMode != transportMode) {
					continue;
				}
				// A curve can bulge out of the box around its ends by up to its length
				final double length = rail.getLength();
				if (Math.max(start.getX(), end.getX()) + length < box[0] || Math.min(start.getX(), end.getX()) - length > box[2] || Math.max(start.getZ(), end.getZ()) + length < box[1] || Math.min(start.getZ(), end.getZ()) - length > box[3]) {
					continue;
				}
				final int count = Math.max(1, Math.min(MAX_POINTS, (int) Math.ceil(length / POINT_SPACING)));
				final float[] points = new float[(count + 1) * 2];
				boolean inside = false;
				for (int i = 0; i <= count; i++) {
					final Vec3 position = rail.getPosition(length * i / count);
					points[i * 2] = (float) position.x;
					points[i * 2 + 1] = (float) position.z;
					inside |= position.x >= box[0] && position.x <= box[2] && position.z >= box[1] && position.z <= box[3];
				}
				if (!inside) {
					continue;
				}
				if (lines.size() >= MAX_RAILS) {
					truncated = true;
					break;
				}
				lines.add(points);
			}
		}
		for (final int value : box) {
			packet.writeInt(value);
		}
		packet.writeInt(transportMode.ordinal());
		packet.writeBoolean(truncated);
		packet.writeInt(lines.size());
		lines.forEach(points -> {
			packet.writeShort(points.length / 2);
			for (final float value : points) {
				packet.writeFloat(value);
			}
		});
	}

	public static Layout read(FriendlyByteBuf packet) {
		final int[] box = {packet.readInt(), packet.readInt(), packet.readInt(), packet.readInt()};
		final int mode = packet.readInt();
		final boolean truncated = packet.readBoolean();
		final int count = packet.readInt();
		if (count < 0 || count > MAX_RAILS) {
			throw new IllegalArgumentException("Too many rails " + count);
		}
		final List<float[]> lines = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			final int points = packet.readShort();
			if (points < 0 || points > MAX_POINTS + 1) {
				throw new IllegalArgumentException("Too many points " + points);
			}
			final float[] line = new float[points * 2];
			for (int j = 0; j < line.length; j++) {
				line[j] = packet.readFloat();
			}
			lines.add(line);
		}
		return new Layout(box, mode, truncated, lines);
	}

	/**
	 * Keeps a box inside the size the server answers for.
	 */
	public static int[] clampBox(int minX, int minZ, int maxX, int maxZ) {
		final int width = Math.min(Math.max(maxX - minX, 0), MAX_SIZE);
		final int depth = Math.min(Math.max(maxZ - minZ, 0), MAX_SIZE);
		final int centerX = minX + (maxX - minX) / 2, centerZ = minZ + (maxZ - minZ) / 2;
		return new int[]{centerX - width / 2, centerZ - depth / 2, centerX - width / 2 + width, centerZ - depth / 2 + depth};
	}

	public static final class Layout {

		public final int[] box;
		public final int mode;
		public final boolean truncated;
		/**
		 * Each rail as x, z, x, z, ... along it.
		 */
		public final List<float[]> lines;

		private Layout(int[] box, int mode, boolean truncated, List<float[]> lines) {
			this.box = box;
			this.mode = mode;
			this.truncated = truncated;
			this.lines = lines;
		}
	}
}
