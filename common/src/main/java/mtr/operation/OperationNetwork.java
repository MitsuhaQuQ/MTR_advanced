package mtr.operation;

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
		depots.put(depotId, new Depot(depotId, trainCount));
		return this;
	}

	public OperationNetwork addDepotLink(long depotId, long platformId, long millis) {
		depots.get(depotId).links.put(platformId, millis);
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
		private final Map<Long, Long> links = new TreeMap<>();

		private Depot(long id, int trainCount) {
			this.id = id;
			this.trainCount = trainCount;
		}

		public long linkMillis(long platformId) {
			return links.getOrDefault(platformId, UNREACHABLE);
		}
	}
}
