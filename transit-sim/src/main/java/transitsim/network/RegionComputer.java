package transitsim.network;

import java.util.*;

/**
 * Works out the {@link RegionData} from a snapshot of the network, away from the server thread: the regions of the
 * pathfinding boundaries, and how trains can run between platforms ({@link PlatformGraph}).
 */
public final class RegionComputer {

	private RegionComputer() {
	}

	public static <R> RegionData compute(RailGraph<R> graph, Boundaries boundaries, List<SavedRail> platforms, List<SavedRail> sidings) {
		final PathRegions regions = PathRegions.compute(graph, boundaries);

		final Map<Long, int[]> platformRegions = new HashMap<>();
		platforms.forEach(platform -> platformRegions.put(platform.id, regions.railRegions(graph, platform.pos1, platform.pos2)));
		final Map<Long, int[]> sidingRegions = new HashMap<>();
		sidings.forEach(siding -> sidingRegions.put(siding.id, regions.railRegions(graph, siding.pos1, siding.pos2)));

		return new RegionData(boundaries.copy(), regions.count, regions.uncutNodes, regions.uncutSegments, regions.missingNodes, regions.missingSegments, platformRegions, sidingRegions, PlatformGraph.compute(graph, regions, platforms));
	}

	/**
	 * A platform or siding, copied from the server's data.
	 */
	public static final class SavedRail {

		public final long id;
		public final long pos1;
		public final long pos2;
		public final long stationId;
		public final int transportMode;

		public SavedRail(long id, long pos1, long pos2, long stationId, int transportMode) {
			this.id = id;
			this.pos1 = pos1;
			this.pos2 = pos2;
			this.stationId = stationId;
			this.transportMode = transportMode;
		}
	}
}
