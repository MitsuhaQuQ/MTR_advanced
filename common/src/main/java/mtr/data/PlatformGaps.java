package mtr.data;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import transitsim.geometry.PlatformEdges;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The gap between a car and the platform where its doors open, measured off the blocks there once per platform and car
 * (0.88.0, diagonal and curved platforms, tier 1): a gap wider than the usual one by more than {@link
 * PlatformEdges#WARN_EXTRA_GAP}, or blocks inside the car's outline, are told in the server log once and kept for the web
 * editor's check list.
 */
public final class PlatformGaps {

	/**
	 * Measured: the platform's ID with the train and the car, so each car is measured once at each platform while the
	 * server runs
	 */
	private static final Set<String> MEASURED = ConcurrentHashMap.newKeySet();
	/**
	 * The worst found at each platform with a problem, by platform ID, for the web editor
	 */
	private static final Map<Long, Gap> PROBLEMS = new ConcurrentHashMap<>();

	private static final double STEP_ALONG = 0.5;
	private static final double STEP_ACROSS = 1D / 16;
	private static final double SCAN_ACROSS = 3;

	private PlatformGaps() {
	}

	public static void measure(Level world, Train train, int car, double carX, double carY, double carZ, float carYaw, float carPitch, double length, int width, boolean left, boolean right) {
		if (train.path == null || train.path.isEmpty()) {
			return;
		}
		final int index = Math.max(0, Math.min(train.path.size() - 1, train.nextStoppingIndex));
		final long platformId = train.path.get(index).savedRailBaseId;
		if (platformId == 0 || !MEASURED.add(platformId + " " + train.id + " " + car)) {
			return;
		}
		final double halfWidth = width / 2D;
		Gap worst = null;
		for (final int side : new int[]{-1, 1}) {
			if (side < 0 ? !left : !right) {
				continue;
			}
			final Gap gap = measureSide(world, carX, carY, carZ, carYaw, carPitch, length, halfWidth, side);
			if (gap != null && (worst == null || gap.isWorseThan(worst))) {
				worst = gap;
			}
		}
		if (worst != null && worst.isProblem()) {
			worst.trainType = train.baseTrainType;
			final Gap kept = PROBLEMS.get(platformId);
			if (kept == null || worst.isWorseThan(kept)) {
				PROBLEMS.put(platformId, worst);
				System.out.printf("Minecraft Transit Railway: platform %d has %s beside %s trains near (%d, %d, %d); a platform on a curve needs its edge set out from the car, and a radius of at least %.0f blocks for cars of %d blocks keeps the gap small: the platform edge block cuts the edge along the track%n",
						platformId, worst.describe(), train.baseTrainType, Math.round(worst.x), Math.round(worst.y), Math.round(worst.z), PlatformEdges.smallestRadius(length), Math.round(length));
			}
		}
	}

	/**
	 * Measured across the car from its side outwards, every half block along it, at the height of the car's floor and a
	 * block up: the nearest block surface there.
	 */
	private static Gap measureSide(Level world, double carX, double carY, double carZ, float carYaw, float carPitch, double length, double halfWidth, int side) {
		final double halfLength = length / 2;
		double widest = -1;
		double narrowest = Double.MAX_VALUE;
		Vec3 widestAt = null;
		int found = 0;
		int samples = 0;
		for (double along = -halfLength + STEP_ALONG / 2; along < halfLength; along += STEP_ALONG) {
			samples++;
			double distance = -1;
			for (double across = halfWidth - 0.5; across <= halfWidth + SCAN_ACROSS && distance < 0; across += STEP_ACROSS) {
				final Vec3 point = new Vec3(side * across, 0, along).xRot(carPitch).yRot(carYaw).add(carX, carY, carZ);
				final int floorRow = (int) Math.floor(carY);
				for (int row = floorRow; row <= floorRow + 1 && distance < 0; row++) {
					if (isSolid(world, point.x, row + 0.9, point.z)) {
						distance = across;
					}
				}
			}
			if (distance >= 0) {
				found++;
				final double gap = distance - halfWidth;
				if (gap > widest) {
					widest = gap;
					widestAt = new Vec3(side * distance, 0, along).xRot(carPitch).yRot(carYaw).add(carX, carY, carZ);
				}
				narrowest = Math.min(narrowest, gap);
			}
		}
		// Not beside a platform for most of the car: nothing to tell
		if (found < samples / 2 || widestAt == null) {
			return null;
		}
		return new Gap(widest, narrowest, widestAt.x, widestAt.y, widestAt.z);
	}

	private static boolean isSolid(Level world, double x, double y, double z) {
		final BlockPos pos = RailwayData.newBlockPos(x, y, z);
		final VoxelShape shape = world.getBlockState(pos).getCollisionShape(world, pos);
		if (shape.isEmpty()) {
			return false;
		}
		final double localX = x - pos.getX();
		final double localY = y - pos.getY();
		final double localZ = z - pos.getZ();
		for (final AABB box : shape.toAabbs()) {
			if (box.minX <= localX && localX <= box.maxX && box.minY <= localY && localY <= box.maxY && box.minZ <= localZ && localZ <= box.maxZ) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return the platforms with a gap or blocks inside cars' outlines, for the web editor
	 */
	public static Map<Long, Gap> getProblems() {
		return new HashMap<>(PROBLEMS);
	}

	public static final class Gap {

		public final double widest;
		public final double narrowest;
		public final double x;
		public final double y;
		public final double z;
		public String trainType = "";

		private Gap(double widest, double narrowest, double x, double y, double z) {
			this.widest = widest;
			this.narrowest = narrowest;
			this.x = x;
			this.y = y;
			this.z = z;
		}

		/**
		 * @return whether blocks stand inside the car's outline
		 */
		public boolean isIntruding() {
			return narrowest < -STEP_ACROSS;
		}

		public boolean isWide() {
			return widest > PlatformEdges.STANDARD_GAP + PlatformEdges.WARN_EXTRA_GAP;
		}

		private boolean isProblem() {
			return isIntruding() || isWide();
		}

		private boolean isWorseThan(Gap other) {
			return isIntruding() != other.isIntruding() ? isIntruding() : Math.max(widest, -narrowest) > Math.max(other.widest, -other.narrowest);
		}

		public String describe() {
			return isIntruding() ? String.format("blocks %.2f blocks inside the cars' outline", -narrowest) : String.format("a gap of up to %.2f blocks", widest);
		}
	}
}
