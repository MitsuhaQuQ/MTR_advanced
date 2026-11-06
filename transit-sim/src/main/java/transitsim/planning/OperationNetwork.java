package transitsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The parts of the track network that train assignment depends on.
 * Moves that were not added are unreachable, and sorted maps keep every iteration order fixed.
 */
public final class OperationNetwork {

	private final java.util.Map<Long, long[]> turnBackHolds = new java.util.TreeMap<>();

	public static final long UNREACHABLE = -1;

	private final Map<Long, Long> turnaroundMillis = new TreeMap<>();
	private final Map<Long, Map<Long, Long>> connections = new TreeMap<>();
	private final Map<Long, Depot> depots = new TreeMap<>();
	private final Map<String, Integer> poolFleets = new HashMap<>();

	/**
	 * A turn back rail beyond the platform a train can wait on for its next service from there, leaving the platform free
	 * (混合折返): the run in, and the run back out to the platform, in milliseconds.
	 */
	public OperationNetwork setTurnBackHold(long platformId, long inMillis, long outMillis) {
		turnBackHolds.put(platformId, new long[]{inMillis, outMillis});
		return this;
	}

	/**
	 * @return the run in and out of the turn back rail a train can wait on beyond the platform, or null if there is none
	 */
	public long[] turnBackHold(long platformId) {
		return turnBackHolds.get(platformId);
	}

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

	/**
	 * Has a pool's depots start the week with this many trains between them (P2-C, R3), each at most what it holds, in
	 * the order of their IDs, rather than each as many as it holds.
	 */
	public OperationNetwork setPoolFleet(String poolKey, int trains) {
		if (poolKey != null && trains >= 0) {
			poolFleets.put(poolKey, trains);
		}
		return this;
	}

	/**
	 * @return the trains each depot starts the week with: as many as it holds, or its share of its pool's when set
	 */
	public Map<Long, Integer> getStartingTrains() {
		final Map<Long, Integer> starting = new TreeMap<>();
		final Map<String, Integer> left = new HashMap<>(poolFleets);
		depots.values().forEach(depot -> {
			final Integer poolLeft = left.get(depot.poolKey);
			if (poolLeft == null) {
				starting.put(depot.id, depot.trainCount);
			} else {
				final int trains = Math.min(depot.trainCount, poolLeft);
				starting.put(depot.id, trains);
				left.put(depot.poolKey, poolLeft - trains);
			}
		});
		return starting;
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
		/**
		 * @return whether a train can get back here from the platform: not found to have no way (a back link of
		 * {@link #UNREACHABLE}, as path generation found)
		 */
		public boolean canGoBack(long platformId) {
			final Long back = backLinks.get(platformId);
			return back == null || back != UNREACHABLE;
		}

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
