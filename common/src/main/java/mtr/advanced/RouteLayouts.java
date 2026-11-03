package mtr.advanced;

import mtr.data.Rail;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import transitsim.network.RailGraph;
import transitsim.network.RailKeys;
import transitsim.network.Straightener;

import java.util.*;

/**
 * The track diagram of a whole route for the advanced editor (design document 6.12, block D): the rails along its path,
 * straightened by {@link Straightener} so the line runs from left to right. Worked out away from the server thread, on
 * a snapshot of the rails.
 */
public final class RouteLayouts {

	public static final int DEFAULT_HALF_WIDTH = 24;
	public static final int MIN_HALF_WIDTH = 8;
	public static final int MAX_HALF_WIDTH = 64;
	private static final int MAX_POINTS = 60000;
	private static final int MAX_RAIL_POINTS = 48;
	private static final double POINT_SPACING = 3;
	private static final long MIN_REQUEST_INTERVAL_MILLIS = 1000;
	private static final Map<UUID, Long> LAST_REQUESTS = new HashMap<>();

	private RouteLayouts() {
	}

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
	 * @param nodes      the route's path as nodes in order, empty if none was found
	 * @param stops      the platform of each stop, as {id, end node, end node}
	 * @param platforms  every platform of the route's transport mode, as {id, end node, end node}
	 * @param halfWidth  how far to each side of the path rails are drawn
	 */
	public static void write(long routeId, RailGraph<Rail> graph, long[] nodes, List<long[]> stops, List<long[]> platforms, int halfWidth, FriendlyByteBuf packet) {
		packet.writeLong(routeId);
		packet.writeInt(halfWidth);
		if (nodes.length < 2) {
			packet.writeBoolean(false);
			return;
		}

		// The path along its curves, with how far along it each node is
		final List<Float> pathPoints = new ArrayList<>();
		final double[] nodeDistances = new double[nodes.length];
		for (int i = 0; i + 1 < nodes.length; i++) {
			final RailGraph.Connection<Rail> connection = graph.find(nodes[i], nodes[i + 1]);
			final float[] points = connection == null ? straight(nodes[i], nodes[i + 1]) : sample(connection.rail);
			for (int j = pathPoints.isEmpty() ? 0 : 2; j < points.length; j++) {
				pathPoints.add(points[j]);
			}
			nodeDistances[i + 1] = nodeDistances[i] + (connection == null ? Math.hypot(points[2] - points[0], points[3] - points[1]) : connection.rail.getLength());
		}
		final float[] path = toArray(pathPoints);
		final Straightener straightener = new Straightener(path, halfWidth);

		// Each stop is where the path leaves its platform, taking the stops in order along the path
		final float[] stopDistances = new float[stops.size()];
		int searchFrom = 0;
		for (int i = 0; i < stops.size(); i++) {
			final UUID platformKey = RailKeys.of(stops.get(i)[1], stops.get(i)[2]);
			stopDistances[i] = -1;
			for (int k = searchFrom; k + 1 < nodes.length; k++) {
				if (RailKeys.of(nodes[k], nodes[k + 1]).equals(platformKey)) {
					stopDistances[i] = (float) (nodeDistances[k + 1] / Math.max(nodeDistances[nodes.length - 1], 1E-9) * straightener.length);
					searchFrom = k + 1;
					break;
				}
			}
		}

		// Only the rails joined to the path by track: lines crossing over or under it aren't part of the diagram
		final Set<Long> connected = connectedNodes(graph, nodes[0]);

		// Every rail once, kept if some of it is near the path
		final List<float[]> pieces = new ArrayList<>();
		final Map<Long, Integer> nodeRails = new HashMap<>();
		int points = 0;
		boolean truncated = false;
		final Set<UUID> done = new HashSet<>();
		for (final RailGraph.Connection<Rail> connection : graph.connections) {
			final UUID key = RailKeys.of(connection.fromPos, connection.toPos);
			if (!connected.contains(connection.fromPos) || !done.add(key)) {
				continue;
			}
			nodeRails.merge(connection.fromPos, 1, Integer::sum);
			nodeRails.merge(connection.toPos, 1, Integer::sum);
			final Rail rail = connection.rail;
			final Vec3 middle = rail.getPosition(rail.getLength() / 2);
			if (straightener.project(middle.x, middle.z, halfWidth + rail.getLength() / 2, Double.NaN) == null) {
				continue;
			}
			for (final float[] piece : straightener.straighten(sample(rail), halfWidth)) {
				if (points + piece.length / 2 > MAX_POINTS) {
					truncated = true;
					break;
				}
				points += piece.length / 2;
				pieces.add(piece);
			}
		}

		final List<float[]> turnouts = new ArrayList<>();
		nodeRails.forEach((node, count) -> {
			if (count >= 3) {
				final double[] projected = straightener.project(x(node), z(node), halfWidth, Double.NaN);
				if (projected != null) {
					turnouts.add(new float[]{(float) projected[0], (float) projected[1]});
				}
			}
		});
		turnouts.sort(Comparator.comparingDouble(turnout -> turnout[0]));

		final List<Object[]> platformLines = new ArrayList<>();
		platforms.forEach(platform -> {
			if (!connected.contains(platform[1]) && !connected.contains(platform[2])) {
				return;
			}
			final double[] end1 = straightener.project(x(platform[1]), z(platform[1]), halfWidth, Double.NaN);
			final double[] end2 = end1 == null ? null : straightener.project(x(platform[2]), z(platform[2]), halfWidth, end1[0]);
			if (end2 != null && Math.abs(end2[0] - end1[0]) < halfWidth * 4) {
				platformLines.add(new Object[]{platform[0], new float[]{(float) end1[0], (float) end1[1], (float) end2[0], (float) end2[1]}});
			}
		});

		packet.writeBoolean(true);
		packet.writeFloat((float) straightener.length);
		packet.writeBoolean(truncated);
		packet.writeInt(stopDistances.length);
		for (final float distance : stopDistances) {
			packet.writeFloat(distance);
		}
		packet.writeInt(pieces.size());
		pieces.forEach(piece -> {
			packet.writeShort(piece.length / 2);
			for (final float value : piece) {
				packet.writeFloat(value);
			}
		});
		packet.writeInt(turnouts.size());
		turnouts.forEach(turnout -> {
			packet.writeFloat(turnout[0]);
			packet.writeFloat(turnout[1]);
		});
		packet.writeInt(platformLines.size());
		platformLines.forEach(platformLine -> {
			packet.writeLong((long) platformLine[0]);
			for (final float value : (float[]) platformLine[1]) {
				packet.writeFloat(value);
			}
		});
	}

	public static Layout read(FriendlyByteBuf packet) {
		final long routeId = packet.readLong();
		final int halfWidth = packet.readInt();
		if (!packet.readBoolean()) {
			return new Layout(routeId, halfWidth, false, 0, false, new float[0], new ArrayList<>(), new ArrayList<>(), new HashMap<>());
		}
		final float length = packet.readFloat();
		final boolean truncated = packet.readBoolean();
		final float[] stops = new float[limit(packet.readInt(), 4096)];
		for (int i = 0; i < stops.length; i++) {
			stops[i] = packet.readFloat();
		}
		final List<float[]> pieces = new ArrayList<>();
		final int pieceCount = limit(packet.readInt(), MAX_POINTS);
		int points = 0;
		for (int i = 0; i < pieceCount; i++) {
			final int count = limit(packet.readShort(), MAX_RAIL_POINTS + 1);
			points += count;
			limit(points, MAX_POINTS);
			final float[] piece = new float[count * 2];
			for (int j = 0; j < piece.length; j++) {
				piece[j] = packet.readFloat();
			}
			pieces.add(piece);
		}
		final List<float[]> turnouts = new ArrayList<>();
		final int turnoutCount = limit(packet.readInt(), MAX_POINTS);
		for (int i = 0; i < turnoutCount; i++) {
			turnouts.add(new float[]{packet.readFloat(), packet.readFloat()});
		}
		final Map<Long, float[]> platforms = new LinkedHashMap<>();
		final int platformCount = limit(packet.readInt(), MAX_POINTS);
		for (int i = 0; i < platformCount; i++) {
			platforms.put(packet.readLong(), new float[]{packet.readFloat(), packet.readFloat(), packet.readFloat(), packet.readFloat()});
		}
		return new Layout(routeId, halfWidth, true, length, truncated, stops, pieces, turnouts, platforms);
	}

	/**
	 * A rail as points along its curve, every few blocks.
	 */
	public static float[] sample(Rail rail) {
		final double length = rail.getLength();
		final int count = Math.max(1, Math.min(MAX_RAIL_POINTS, (int) Math.ceil(length / POINT_SPACING)));
		final float[] points = new float[(count + 1) * 2];
		for (int i = 0; i <= count; i++) {
			final Vec3 position = rail.getPosition(length * i / count);
			points[i * 2] = (float) position.x;
			points[i * 2 + 1] = (float) position.z;
		}
		return points;
	}

	/**
	 * The nodes reached from a node along rails either way, as one-way rails still join the track physically.
	 */
	private static Set<Long> connectedNodes(RailGraph<Rail> graph, long start) {
		final Map<Long, List<Long>> neighbours = new HashMap<>();
		graph.connections.forEach(connection -> {
			neighbours.computeIfAbsent(connection.fromPos, key -> new ArrayList<>()).add(connection.toPos);
			neighbours.computeIfAbsent(connection.toPos, key -> new ArrayList<>()).add(connection.fromPos);
		});
		final Set<Long> reached = new HashSet<>();
		final ArrayDeque<Long> queue = new ArrayDeque<>();
		reached.add(start);
		queue.add(start);
		while (!queue.isEmpty()) {
			for (final long next : neighbours.getOrDefault(queue.poll(), Collections.emptyList())) {
				if (reached.add(next)) {
					queue.add(next);
				}
			}
		}
		return reached;
	}

	private static float[] straight(long node1, long node2) {
		return new float[]{(float) x(node1), (float) z(node1), (float) x(node2), (float) z(node2)};
	}

	/**
	 * A node's middle, where rails run, from its position packed as {@link net.minecraft.core.BlockPos#asLong()}.
	 */
	private static double x(long node) {
		return net.minecraft.core.BlockPos.getX(node) + 0.5;
	}

	private static double z(long node) {
		return net.minecraft.core.BlockPos.getZ(node) + 0.5;
	}

	private static float[] toArray(List<Float> values) {
		final float[] array = new float[values.size()];
		for (int i = 0; i < array.length; i++) {
			array[i] = values.get(i);
		}
		return array;
	}

	private static int limit(int count, int maximum) {
		if (count < 0 || count > maximum) {
			throw new IllegalArgumentException("Bad count " + count);
		}
		return count;
	}

	public static final class Layout {

		public final long routeId;
		public final int halfWidth;
		/**
		 * False when the route's path couldn't be found.
		 */
		public final boolean found;
		public final float length;
		public final boolean truncated;
		/**
		 * How far along the path each stop is, or -1 where the path doesn't run through its platform.
		 */
		public final float[] stops;
		/**
		 * Rails as distance, offset, distance, offset, ...
		 */
		public final List<float[]> pieces;
		public final List<float[]> turnouts;
		/**
		 * Platform ID → its ends as distance, offset, distance, offset.
		 */
		public final Map<Long, float[]> platforms;

		private Layout(long routeId, int halfWidth, boolean found, float length, boolean truncated, float[] stops, List<float[]> pieces, List<float[]> turnouts, Map<Long, float[]> platforms) {
			this.routeId = routeId;
			this.halfWidth = halfWidth;
			this.found = found;
			this.length = length;
			this.truncated = truncated;
			this.stops = stops;
			this.pieces = pieces;
			this.turnouts = turnouts;
			this.platforms = platforms;
		}
	}
}
