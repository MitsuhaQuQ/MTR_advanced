package transitsim.network;

import java.util.*;

/**
 * What is worked out from the pathfinding boundaries, which a host can send to its players: the boundaries themselves,
 * which regions each platform and siding is in, and the platforms each stop of a route could use instead (design document
 * 5.13 and 6.12, block P).
 */
public final class RegionData {

	public final Boundaries boundaries;
	public final int regionCount;
	public final List<Long> uncutNodes;
	public final List<UUID> uncutSegments;
	public final List<Long> missingNodes;
	public final List<UUID> missingSegments;
	public final Map<Long, int[]> platformRegions;
	public final Map<Long, int[]> sidingRegions;
	/**
	 * Route ID → stop index → platform IDs a train on this route could stop at instead: platforms of the same station,
	 * in the same region, which can be reached from the stop before and lead on to the stop after. Stops with no other
	 * platform are left out.
	 */
	public final Map<Long, Map<Integer, long[]>> routeCandidates;

	public static final RegionData EMPTY = new RegionData(new Boundaries(), 0, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());

	public RegionData(Boundaries boundaries, int regionCount, List<Long> uncutNodes, List<UUID> uncutSegments, List<Long> missingNodes, List<UUID> missingSegments, Map<Long, int[]> platformRegions, Map<Long, int[]> sidingRegions, Map<Long, Map<Integer, long[]>> routeCandidates) {
		this.boundaries = boundaries;
		this.regionCount = regionCount;
		this.uncutNodes = uncutNodes;
		this.uncutSegments = uncutSegments;
		this.missingNodes = missingNodes;
		this.missingSegments = missingSegments;
		this.platformRegions = platformRegions;
		this.sidingRegions = sidingRegions;
		this.routeCandidates = routeCandidates;
	}

	/**
	 * @return the platforms a stop could use, always including the one the route uses now
	 */
	public long[] getCandidates(long routeId, int stopIndex, long platformId) {
		final Map<Integer, long[]> stops = routeCandidates.get(routeId);
		final long[] candidates = stops == null ? null : stops.get(stopIndex);
		return candidates == null ? new long[]{platformId} : candidates;
	}

	public boolean isBoundaryNode(long pos) {
		return boundaries.nodes.contains(pos);
	}
}
