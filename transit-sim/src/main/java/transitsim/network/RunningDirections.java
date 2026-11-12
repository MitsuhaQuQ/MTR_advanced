package transitsim.network;

import java.util.*;

/**
 * The way each track of a double (or quadruple) track line runs, by the line's running side (user, 2026-11-12): looking
 * the way a train goes, the track on its running side is that way's. Trains going that way then follow each other along
 * it section by section; a train running it the other way takes its whole stretch at once, as on a single track.
 * <ul>
 * <li>Tracks run side by side when they are of the same line, near parallel and a few blocks apart. Of two, the one on the
 * running side runs that way; of four, both on that side of the middle (方向别, 上上下下); of an odd number, the middle one
 * has no way of its own. A track alone, as a single track line, a turn back rail or a depot's, has none.</li>
 * <li>Each line has its running side, right or left; one line has one. Tracks of lines with different sides have none.</li>
 * <li>A way goes on along the track: through every node joining just two rails it carries on, so where the tracks of a line
 * swap sides (上下线换位, as over a flyover) each keeps its way. Where the parts of such a run of rails don't agree, the
 * longer part decides. Turnouts and crossings (nodes of three rails or more) end a run.</li>
 * </ul>
 * Only the rails decide it, so both hosts work it out the same way.
 */
public final class RunningDirections {

	public static final int RIGHT = 1;
	public static final int LEFT = -1;

	/**
	 * How far apart (blocks) two tracks running side by side may be, and how close at least; and how far from parallel.
	 */
	public static final double MAX_APART = 12;
	private static final double MIN_APART = 1.5;
	private static final double MAX_SIN = Math.sin(Math.toRadians(15));
	/**
	 * How far apart along a rail its sample points are, at most, in blocks.
	 */
	public static final double SAMPLE_SPACING = 8;
	private static final double CELL = 16;

	private RunningDirections() {
	}

	/**
	 * A rail as the host knows it.
	 */
	public static final class Rail {

		public final UUID key;
		public final long nodeA;
		public final long nodeB;
		/**
		 * Points along the rail from node A to node B, x and z one after the other, at most {@link #SAMPLE_SPACING} apart;
		 * the first is at node A and the last at node B.
		 */
		public final double[] points;
		/**
		 * The lines running on it, empty for none (a depot's track)
		 */
		public final Set<String> lines;
		/**
		 * {@link #RIGHT} or {@link #LEFT}, the running side of its lines, or 0 if they don't agree or it has none
		 */
		public final int side;

		public Rail(UUID key, long nodeA, long nodeB, double[] points, Set<String> lines, int side) {
			this.key = key;
			this.nodeA = nodeA;
			this.nodeB = nodeB;
			this.points = points;
			this.lines = lines;
			this.side = side;
		}

		private double length() {
			double length = 0;
			for (int i = 2; i < points.length; i += 2) {
				length += Math.hypot(points[i] - points[i - 2], points[i + 1] - points[i - 1]);
			}
			return length;
		}
	}

	/**
	 * @return for each rail with a way of its own, 1 if trains run it from its smaller node to its larger, -1 the other way;
	 * rails with none are left out
	 */
	public static Map<UUID, Integer> compute(Collection<Rail> rails) {
		final List<Rail> list = new ArrayList<>(rails);
		// The parts of every rail, by the cells of a grid they are in
		final Map<Long, List<int[]>> grid = new HashMap<>();
		for (int r = 0; r < list.size(); r++) {
			final double[] points = list.get(r).points;
			for (int i = 0; i + 3 < points.length; i += 2) {
				final double minX = Math.min(points[i], points[i + 2]) - MAX_APART, maxX = Math.max(points[i], points[i + 2]) + MAX_APART;
				final double minZ = Math.min(points[i + 1], points[i + 3]) - MAX_APART, maxZ = Math.max(points[i + 1], points[i + 3]) + MAX_APART;
				for (long cx = (long) Math.floor(minX / CELL); cx <= (long) Math.floor(maxX / CELL); cx++) {
					for (long cz = (long) Math.floor(minZ / CELL); cz <= (long) Math.floor(maxZ / CELL); cz++) {
						grid.computeIfAbsent(cx * 0x100000000L + (cz & 0xFFFFFFFFL), key -> new ArrayList<>()).add(new int[]{r, i});
					}
				}
			}
		}

		// Each rail's say, in its own direction from node A to node B: blocks of it that run that way, less blocks that run the
		// other way
		final double[] votes = new double[list.size()];
		for (int r = 0; r < list.size(); r++) {
			final Rail rail = list.get(r);
			if (rail.side == 0 || rail.lines.isEmpty()) {
				continue;
			}
			final double[] points = rail.points;
			for (int i = 0; i + 3 < points.length; i += 2) {
				final double tx = points[i + 2] - points[i], tz = points[i + 3] - points[i + 1];
				final double length = Math.hypot(tx, tz);
				if (length < 1E-6) {
					continue;
				}
				final double ux = tx / length, uz = tz / length;
				final double px = (points[i] + points[i + 2]) / 2, pz = (points[i + 1] + points[i + 3]) / 2;
				// The nearest part of each other rail of the same line running beside this part: which side it is on
				final Map<Integer, Double> beside = new HashMap<>();
				final List<int[]> candidates = grid.get((long) Math.floor(px / CELL) * 0x100000000L + ((long) Math.floor(pz / CELL) & 0xFFFFFFFFL));
				if (candidates == null) {
					continue;
				}
				for (final int[] candidate : candidates) {
					final int s = candidate[0];
					if (s == r) {
						continue;
					}
					final Rail other = list.get(s);
					if (other.side != rail.side || Collections.disjoint(other.lines, rail.lines)) {
						continue;
					}
					final double[] q = other.points;
					final int j = candidate[1];
					final double sx = q[j + 2] - q[j], sz = q[j + 3] - q[j + 1];
					final double otherLength = Math.hypot(sx, sz);
					if (otherLength < 1E-6 || Math.abs(ux * sz - uz * sx) / otherLength > MAX_SIN) {
						continue;
					}
					final double t = Math.max(0, Math.min(1, ((px - q[j]) * sx + (pz - q[j + 1]) * sz) / (otherLength * otherLength)));
					final double cx = q[j] + t * sx - px, cz = q[j + 1] + t * sz - pz;
					final double distance = Math.hypot(cx, cz);
					// Beside, not ahead or behind: what is between them runs across the track
					if (distance < MIN_APART || distance > MAX_APART || Math.abs(cx * ux + cz * uz) > distance * 0.5) {
						continue;
					}
					// Looking along the part (x east, z south, seen from above), positive is to the right
					final double across = ux * cz - uz * cx;
					final Double nearest = beside.get(s);
					if (nearest == null || Math.abs(across) < Math.abs(nearest)) {
						beside.put(s, across);
					}
				}
				if (beside.isEmpty()) {
					continue;
				}
				int right = 0;
				int left = 0;
				for (final double across : beside.values()) {
					if (across > 0) {
						right++;
					} else {
						left++;
					}
				}
				if (right == left) {
					continue;
				}
				// More tracks to its left: it is on the right of the bundle, so with right hand running trains go its way
				votes[r] += (left > right ? 1 : -1) * rail.side * length;
			}
		}

		// Runs of rails through nodes of two rails
		final Map<Long, List<Integer>> atNode = new HashMap<>();
		for (int r = 0; r < list.size(); r++) {
			atNode.computeIfAbsent(list.get(r).nodeA, key -> new ArrayList<>()).add(r);
			atNode.computeIfAbsent(list.get(r).nodeB, key -> new ArrayList<>()).add(r);
		}
		final Map<UUID, Integer> directions = new HashMap<>();
		final boolean[] done = new boolean[list.size()];
		for (int start = 0; start < list.size(); start++) {
			if (done[start]) {
				continue;
			}
			// Each rail of the run with which way it is walked (1: from node A to node B)
			final List<int[]> run = new ArrayList<>();
			done[start] = true;
			run.add(new int[]{start, 1});
			for (final boolean forwards : new boolean[]{true, false}) {
				int rail = start;
				long node = forwards ? list.get(start).nodeB : list.get(start).nodeA;
				while (true) {
					final List<Integer> here = atNode.get(node);
					if (here == null || here.size() != 2) {
						break;
					}
					final int next = here.get(0) == rail ? here.get(1) : here.get(0);
					if (done[next]) {
						break;
					}
					done[next] = true;
					final Rail nextRail = list.get(next);
					final boolean nextFromA = nextRail.nodeA == node;
					// Walked away from the start: forwards along the run if entering at node A when walking forwards
					final int along = forwards == nextFromA ? 1 : -1;
					if (forwards) {
						run.add(new int[]{next, along});
					} else {
						run.add(0, new int[]{next, along});
					}
					rail = next;
					node = nextFromA ? nextRail.nodeB : nextRail.nodeA;
				}
			}
			double sum = 0;
			for (final int[] each : run) {
				sum += votes[each[0]] * each[1];
			}
			if (Math.abs(sum) < 1E-6) {
				continue;
			}
			for (final int[] each : run) {
				final Rail rail = list.get(each[0]);
				// From node A to node B along the run's way, then from the smaller node to the larger
				final int fromA = (sum > 0 ? 1 : -1) * each[1];
				directions.put(rail.key, rail.nodeA < rail.nodeB ? fromA : -fromA);
			}
		}
		return directions;
	}

	/**
	 * @return points along a straight rail from one place to another, at most {@link #SAMPLE_SPACING} apart
	 */
	public static double[] straight(double x1, double z1, double x2, double z2) {
		final int pieces = Math.max(1, (int) Math.ceil(Math.hypot(x2 - x1, z2 - z1) / SAMPLE_SPACING));
		final double[] points = new double[(pieces + 1) * 2];
		for (int i = 0; i <= pieces; i++) {
			points[i * 2] = x1 + (x2 - x1) * i / pieces;
			points[i * 2 + 1] = z1 + (z2 - z1) * i / pieces;
		}
		return points;
	}
}
