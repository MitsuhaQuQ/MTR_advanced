package transitsim.network;


import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only snapshot of the rail connections, independent of Minecraft so it can be searched from another thread.
 * A search state is a directed connection, which fixes both the node and the direction of travel.
 */
public final class RailGraph<R> {

	public final List<Connection<R>> connections;
	private final Map<Long, List<Connection<R>>> outgoing = new HashMap<>();
	private final Map<Long, Connection<R>> reverse = new HashMap<>();
	private final Map<Long, List<Connection<R>>> byNode = new HashMap<>();
	private final Map<Long, Long> components = new HashMap<>();
	final double maxChordPerCost;
	/**
	 * The regions searches are kept to, or null when there are no pathfinding boundaries.
	 */
	private PathRegions regions;

	private RailGraph(List<Connection<R>> sortedConnections) {
		connections = Collections.unmodifiableList(sortedConnections);
		final Map<String, Connection<R>> byEnds = new HashMap<>();
		double tempMaxChordPerCost = 0;

		for (int i = 0; i < connections.size(); i++) {
			final Connection<R> connection = connections.get(i);
			connection.index = i;
			outgoing.computeIfAbsent(connection.fromPos, key -> new ArrayList<>()).add(connection);
			byNode.computeIfAbsent(connection.toPos, key -> new ArrayList<>()).add(connection);
			byEnds.put(connection.fromPos + ">" + connection.toPos, connection);
			if (connection.cost > 0) {
				tempMaxChordPerCost = Math.max(tempMaxChordPerCost, connection.chordTo(connection.fromX, connection.fromZ) / connection.cost);
			}
		}

		connections.forEach(connection -> {
			final Connection<R> opposite = byEnds.get(connection.toPos + ">" + connection.fromPos);
			if (opposite != null) {
				reverse.put(connection.key(), opposite);
			}
		});
		maxChordPerCost = tempMaxChordPerCost;
		// One way rails still connect their ends here, so this only rules out routes that can't exist
		connections.forEach(connection -> components.put(findComponent(connection.fromPos), findComponent(connection.toPos)));
		// Point every node straight at its root, so the graph is never written to again and can be shared between threads
		new ArrayList<>(components.keySet()).forEach(pos -> components.put(pos, findComponent(pos)));
	}

	/**
	 * Only used while building, as it compresses the paths it follows.
	 */
	private long findComponent(long pos) {
		long root = pos;
		while (components.containsKey(root) && components.get(root) != root) {
			root = components.get(root);
		}
		long current = pos;
		while (current != root) {
			final long next = components.getOrDefault(current, root);
			components.put(current, root);
			current = next;
		}
		return root;
	}

	public boolean isConnected(long pos1, long pos2) {
		return hasNode(pos1) && hasNode(pos2) && components.getOrDefault(pos1, pos1).longValue() == components.getOrDefault(pos2, pos2).longValue();
	}

	public List<Connection<R>> outgoing(long pos) {
		return outgoing.getOrDefault(pos, Collections.emptyList());
	}

	public PathRegions getRegions() {
		return regions;
	}

	/**
	 * Keeps searches to the regions of the pathfinding boundaries. Without boundaries the regions only repeat the
	 * connected parts, so they are left out.
	 */
	public void setRegions(Boundaries boundaries) {
		regions = boundaries == null || boundaries.isEmpty() ? null : PathRegions.compute(this, boundaries);
	}

	Connection<R> reverseOf(Connection<R> connection) {
		return reverse.get(connection.key());
	}

	public Connection<R> find(long fromPos, long toPos) {
		for (final Connection<R> connection : outgoing(fromPos)) {
			if (connection.toPos == toPos) {
				return connection;
			}
		}
		return null;
	}

	public boolean hasNode(long pos) {
		return outgoing.containsKey(pos) || byNode.containsKey(pos);
	}

	public enum Kind {
		NORMAL, PLATFORM, TURN_BACK, DISABLED
	}

	public static final class Connection<R> {

		public final long fromPos;
		public final long toPos;
		public final R rail;
		public final Kind kind;
		public final double cost;
		final int fromX;
		final int fromZ;
		final int toX;
		final int toY;
		final int toZ;
		private final int startFacing;
		private final int endFacing;
		private int index;

		private Connection(long fromPos, int fromX, int fromZ, int startFacing, long toPos, int toX, int toY, int toZ, int endFacing, Kind kind, double cost, R rail) {
			this.fromPos = fromPos;
			this.fromX = fromX;
			this.fromZ = fromZ;
			this.startFacing = startFacing;
			this.toPos = toPos;
			this.toX = toX;
			this.toY = toY;
			this.toZ = toZ;
			this.endFacing = endFacing;
			this.kind = kind;
			this.cost = cost;
			this.rail = rail;
		}

		public int index() {
			return index;
		}

		public boolean continuesFrom(Connection<R> previous) {
			return kind != Kind.DISABLED && startFacing != previous.endFacing;
		}

		double chordTo(int x, int z) {
			final double differenceX = x - toX;
			final double differenceZ = z - toZ;
			return Math.sqrt(differenceX * differenceX + differenceZ * differenceZ);
		}

		private long key() {
			return index;
		}
	}

	public static final class Builder<R> {

		private final List<Connection<R>> connections = new ArrayList<>();

		public Builder<R> add(long fromPos, int fromX, int fromZ, int startFacing, long toPos, int toX, int toY, int toZ, int endFacing, Kind kind, double cost, R rail) {
			connections.add(new Connection<>(fromPos, fromX, fromZ, startFacing, toPos, toX, toY, toZ, endFacing, kind, cost, rail));
			return this;
		}

		public RailGraph<R> build() {
			final List<Connection<R>> sorted = new ArrayList<>(connections);
			sorted.sort(Comparator.<Connection<R>>comparingInt(connection -> connection.fromX).thenComparingInt(connection -> connection.fromZ).thenComparingLong(connection -> connection.fromPos).thenComparingInt(connection -> connection.toX).thenComparingInt(connection -> connection.toZ).thenComparingLong(connection -> connection.toPos));
			return new RailGraph<>(sorted);
		}
	}
}
