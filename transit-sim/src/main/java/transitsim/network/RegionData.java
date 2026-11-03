package transitsim.network;

import java.util.*;

/**
 * What is worked out from the rails and the pathfinding boundaries, which a host can send to its players: the boundaries
 * themselves, which regions each platform and siding is in, and how trains can run between platforms (design document 5.13
 * and 6.12, blocks P and D).
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
	public final PlatformGraph platformGraph;

	public static final RegionData EMPTY = new RegionData(new Boundaries(), 0, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new HashMap<>(), new HashMap<>(), PlatformGraph.EMPTY);

	public RegionData(Boundaries boundaries, int regionCount, List<Long> uncutNodes, List<UUID> uncutSegments, List<Long> missingNodes, List<UUID> missingSegments, Map<Long, int[]> platformRegions, Map<Long, int[]> sidingRegions, PlatformGraph platformGraph) {
		this.boundaries = boundaries;
		this.regionCount = regionCount;
		this.uncutNodes = uncutNodes;
		this.uncutSegments = uncutSegments;
		this.missingNodes = missingNodes;
		this.missingSegments = missingSegments;
		this.platformRegions = platformRegions;
		this.sidingRegions = sidingRegions;
		this.platformGraph = platformGraph;
	}

	public boolean isBoundaryNode(long pos) {
		return boundaries.nodes.contains(pos);
	}
}
