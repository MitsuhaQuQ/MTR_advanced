package mtr.advanced;

import mtr.data.Rail;
import transitsim.network.*;

import java.util.Map;
import java.util.UUID;

/**
 * Turns MTR's rails into the network snapshot of the simulation component (design document 5.11, 6.12).
 */
public final class RailNetworks {

	private static final RailAttributes<Rail> ATTRIBUTES = new RailAttributes<Rail>() {
		@Override
		public SectionType getType(Rail rail) {
			switch (rail.railType) {
				case PLATFORM:
					return SectionType.PLATFORM;
				case SIDING:
					return SectionType.SIDING;
				case TURN_BACK:
					return SectionType.TURN_BACK;
				default:
					return SectionType.NORMAL;
			}
		}

		@Override
		public boolean canAccelerate(Rail rail) {
			return rail.railType.canAccelerate;
		}

		@Override
		public double getSpeedLimit(Rail rail) {
			return rail.railType.maxBlocksPerTick;
		}

		@Override
		public double getLength(Rail rail) {
			return rail.getLength();
		}
	};

	private RailNetworks() {
	}

	/**
	 * @param savedRails the platform or siding on each rail, keyed like {@link PathBoundaries#segmentKey}
	 * @param version    the order the network is published in
	 */
	public static Network compress(RailGraph<Rail> graph, PathRegions regions, Boundaries boundaries, Map<UUID, Long> savedRails, long version) {
		return NetworkBuilder.build(graph, regions, boundaries, savedRails, ATTRIBUTES, version);
	}
}
