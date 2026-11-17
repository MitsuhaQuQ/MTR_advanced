package mtr.data;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import transitsim.geometry.PlatformEdges;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A platform's rail as its edge sees it (0.88.0, diagonal and curved platforms): the cars that stop there, the most the
 * middle of a car stands in from the rail anywhere along it, and the nearest point of it to a place with the rail's
 * direction there. Used by both sides: the web editor's check list on the client, the platform edge block on the server.
 */
public final class PlatformCurves {

	/**
	 * A car of the longest MTR trains, with the gap to the next one, and two blocks wide, when no depot stops there
	 */
	public static final int DEFAULT_SPACING = 25;
	public static final int DEFAULT_WIDTH = 2;
	private static final double STEP = 0.5;

	private PlatformCurves() {
	}

	/**
	 * @return the longest car spacing and the widest car of the trains of sidings whose depots run a route stopping at the
	 * platform, as {spacing, width}; the defaults when none does
	 */
	public static int[] carsAt(long platformId, Collection<Route> routes, Collection<Siding> sidings, Map<Long, Depot> sidingIdToDepot) {
		final Set<Long> routeIds = new HashSet<>();
		routes.forEach(route -> {
			if (route.platformIds.stream().anyMatch(routePlatform -> routePlatform.platformId == platformId)) {
				routeIds.add(route.id);
			}
		});
		int spacing = 0;
		int width = 0;
		for (final Siding siding : sidings) {
			final Depot depot = sidingIdToDepot.get(siding.id);
			if (depot == null || siding.getBaseTrainType() == null) {
				continue;
			}
			final Set<Long> runs = siding.getRouteIds().isEmpty() ? new HashSet<>(depot.routeIds) : siding.getRouteIds();
			if (runs.stream().anyMatch(routeIds::contains)) {
				spacing = Math.max(spacing, TrainType.getSpacing(siding.getBaseTrainType()));
				width = Math.max(width, TrainType.getWidth(siding.getBaseTrainType()));
			}
		}
		return new int[]{spacing == 0 ? DEFAULT_SPACING : spacing, width == 0 ? DEFAULT_WIDTH : width};
	}

	/**
	 * @return the rail of a platform, either way round, or null if it isn't there
	 */
	public static Rail railOf(SavedRailBase platform, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		final List<BlockPos> ends = platform.getOrderedPositions(BlockPos.ZERO, false);
		final Map<BlockPos, Rail> from = rails.get(ends.get(0));
		return from == null ? null : from.get(ends.get(1));
	}

	/**
	 * @return the most the middle of a car of this length stands in from the rail anywhere along it, either side
	 */
	public static double largestSagitta(Rail rail, double carLength) {
		final double length = rail.getLength();
		final double half = Math.min(carLength, length) / 2;
		double largest = 0;
		for (double along = half; along <= length - half + 1E-9; along += STEP) {
			largest = Math.max(largest, Math.abs(sagittaAt(rail, along, half, 0, 0, true)));
		}
		return largest;
	}

	/**
	 * How far from the rail the platform's edge is at a distance along it, on a side (0.96.0): as the edge blocks are cut and
	 * drawn ({@code PlatformEdgeCurves}), the cars' half width and how far the middle of a car centred there (kept off the
	 * rail's ends) stands in towards that side. On a transition curve this changes along the rail.
	 *
	 * @param side 1 for the left of the rail's direction ((dz, -dx) for a direction (dx, dz)), -1 for the right
	 * @param cars {@link #carsAt}: the longest car and the widest train
	 */
	public static double edgeOffsetAt(Rail rail, double along, int side, int[] cars) {
		final double length = rail.getLength();
		final double clamped = Math.max(0, Math.min(length, along));
		final Vec3 ahead = rail.getPosition(Math.min(length, clamped + 0.25));
		final Vec3 behind = rail.getPosition(Math.max(0, clamped - 0.25));
		final double directionX = ahead.x - behind.x;
		final double directionZ = ahead.z - behind.z;
		final double directionLength = Math.hypot(directionX, directionZ);
		final double sideX = directionLength == 0 ? 0 : directionZ / directionLength * side;
		final double sideZ = directionLength == 0 ? 0 : -directionX / directionLength * side;
		final double half = Math.min(cars[0], length) / 2;
		final double centred = Math.max(half, Math.min(length - half, clamped));
		return PlatformEdges.edgeOffset(cars[1] / 2D, sagittaAt(rail, centred, half, sideX, sideZ, false));
	}

	/**
	 * @return how far the middle of a car centred at this distance along the rail stands in from it towards a side, or
	 * either way (its size) when {@code anySide}
	 */
	private static double sagittaAt(Rail rail, double along, double half, double sideX, double sideZ, boolean anySide) {
		final Vec3 point = rail.getPosition(along);
		final Vec3 previous = rail.getPosition(along - half);
		final Vec3 next = rail.getPosition(along + half);
		if (anySide) {
			return Math.hypot((previous.x + next.x) / 2 - point.x, (previous.z + next.z) / 2 - point.z);
		}
		return PlatformEdges.signedSagitta(previous.x, previous.z, point.x, point.z, next.x, next.z, sideX, sideZ);
	}

	/**
	 * The point of a rail nearest a place, on the horizontal plane, with the rail's direction there and the side the place
	 * is on, and how far the middle of a car centred there stands in towards that side.
	 */
	public static Nearest nearest(Rail rail, double x, double z, double carLength) {
		final double length = rail.getLength();
		double best = Double.MAX_VALUE;
		double bestAlong = 0;
		for (double along = 0; along <= length; along += STEP / 4) {
			final Vec3 point = rail.getPosition(along);
			final double distance = Math.hypot(point.x - x, point.z - z);
			if (distance < best) {
				best = distance;
				bestAlong = along;
			}
		}
		final Vec3 point = rail.getPosition(bestAlong);
		final Vec3 ahead = rail.getPosition(Math.min(length, bestAlong + 0.25));
		final Vec3 behind = rail.getPosition(Math.max(0, bestAlong - 0.25));
		final double directionX = ahead.x - behind.x;
		final double directionZ = ahead.z - behind.z;
		final double directionLength = Math.hypot(directionX, directionZ);
		// Square to the rail, towards the place
		double sideX = directionLength == 0 ? x - point.x : directionZ / directionLength;
		double sideZ = directionLength == 0 ? z - point.z : -directionX / directionLength;
		if (sideX * (x - point.x) + sideZ * (z - point.z) < 0) {
			sideX = -sideX;
			sideZ = -sideZ;
		}
		final double half = Math.min(carLength, length) / 2;
		final double along = Math.max(half, Math.min(length - half, bestAlong));
		return new Nearest(point, sideX, sideZ, best, sagittaAt(rail, along, half, sideX, sideZ, false));
	}

	public static final class Nearest {

		public final Vec3 point;
		public final double sideX;
		public final double sideZ;
		public final double distance;
		public final double signedSagitta;

		private Nearest(Vec3 point, double sideX, double sideZ, double distance, double signedSagitta) {
			this.point = point;
			this.sideX = sideX;
			this.sideZ = sideZ;
			this.distance = distance;
			this.signedSagitta = signedSagitta;
		}
	}
}
