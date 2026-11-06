package transitsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The parts of the track network that train assignment depends on.
 * Moves that were not added are unreachable, and sorted maps keep every iteration order fixed.
 */
public final class OperationNetwork {

	public static final long UNREACHABLE = -1;

	private final Map<Long, Long> turnaroundMillis = new TreeMap<>();
	private final Map<Long, Map<Long, Long>> connections = new TreeMap<>();
	private final Map<Long, Depot> depots = new TreeMap<>();

	public OperationNetwork setTurnaround(long platformId, long millis) {
		turnaroundMillis.put(platformId, millis);
		return this;
	}

	public OperationNetwork addConnection(long fromPlatformId, long toPlatformId, long millis) {
		connections.computeIfAbsent(fromPlatformId, key -> new TreeMap<>()).put(toPlatformId, millis);
		return this;
	}

	public OperationNetwork addDepot(long depotId, int trainCount) {
		return addDepot(depotId, trainCount, null);
	}

	/**
	 * @param poolKey the depots whose trains run each other's duties and go back to whichever needs one next, as the
	 *                sidings of one pool do; null for a pool of its own
	 */
	public OperationNetwork addDepot(long depotId, int trainCount, String poolKey) {
		depots.put(depotId, new Depot(depotId, trainCount, poolKey == null ? "#" + depotId : poolKey));
		return this;
	}

	public OperationNetwork addDepotLink(long depotId, long platformId, long millis) {
		depots.get(depotId).links.put(platformId, millis);
		return this;
	}

	/**
	 * How long a train takes from a platform back to the depot, as measured on the rails.
	 */
	public OperationNetwork addDepotBackLink(long depotId, long platformId, long millis) {
		depots.get(depotId).backLinks.put(platformId, millis);
		return this;
	}

	public long turnaroundMillis(long platformId) {
		return turnaroundMillis.getOrDefault(platformId, 0L);
	}

	public long connectionMillis(long fromPlatformId, long toPlatformId) {
		if (fromPlatformId == toPlatformId) {
			return 0;
		}
		final Map<Long, Long> destinations = connections.get(fromPlatformId);
		return destinations == null ? UNREACHABLE : destinations.getOrDefault(toPlatformId, UNREACHABLE);
	}

	public List<Depot> depots() {
		return Collections.unmodifiableList(new ArrayList<>(depots.values()));
	}

	public Depot depot(long depotId) {
		return depots.get(depotId);
	}

	public static final class Depot {

		public final long id;
		public final int trainCount;
		public final String poolKey;
		private final Map<Long, Long> links = new TreeMap<>();
		private final Map<Long, Long> backLinks = new TreeMap<>();

		private Depot(long id, int trainCount, String poolKey) {
			this.poolKey = poolKey;
			this.id = id;
			this.trainCount = trainCount;
		}

		public long linkMillis(long platformId) {
			return links.getOrDefault(platformId, UNREACHABLE);
		}

		/**
		 * @return how long a train takes from the platform back to the depot: measured, or the way out from there, or, with
		 * neither, the longest way out the depot has, rather than nothing as if the train were back the moment it arrived
		 */
		public long backMillis(long platformId) {
			final Long back = backLinks.get(platformId);
			if (back != null) {
				return back;
			}
			final Long out = links.get(platformId);
			if (out != null) {
				return out;
			}
			return links.values().stream().mapToLong(Long::longValue).max().orElse(0);
		}
	}
}
