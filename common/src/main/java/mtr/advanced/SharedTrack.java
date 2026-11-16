package mtr.advanced;

import mtr.data.*;
import net.minecraft.core.BlockPos;
import transitsim.network.RailKeys;
import transitsim.network.SharedAreas;

import java.util.*;

/**
 * The track run on by depots of different running diagrams (user, 2026-11-16: every area shared must be one running
 * diagram, the player choosing which line joins which), worked out on the server for its log and, sent on, for the web
 * editor's check list: {@link SharedAreas} over the depots' paths, the depots grouped by the diagrams of their lines
 * ({@link LineDuties#diagramGroupsOf}, with through links and the lines joined in {@link NetworkSettings#diagramJoins}),
 * each area with the lines each group runs there, its length and the stations with platforms on it.
 */
public final class SharedTrack {

	private SharedTrack() {
	}

	/**
	 * @param rails the network's rails, for the length; may be empty
	 * @return the shared areas, in a fixed order
	 */
	public static List<Area> find(Collection<Depot> depots, Collection<Siding> sidings, Collection<Platform> platforms, DataCache dataCache, Map<BlockPos, Map<BlockPos, Rail>> rails) {
		final Map<Long, Set<Long>> units = LineDuties.diagramGroupsOf(depots, dataCache);
		final SharedAreas finder = new SharedAreas();
		sidings.forEach(siding -> {
			final Depot depot = dataCache.sidingIdToDepot.get(siding.id);
			final Set<Long> unit = depot == null ? null : units.get(depot.id);
			if (unit != null && !depot.transportMode.continuousMovement) {
				final String key = unit.toString();
				siding.forEachPathNodes(nodes -> finder.addPath(key, nodes));
			}
		});
		final List<SharedAreas.Area> found = finder.result();
		final List<Area> areas = new ArrayList<>();
		if (found.isEmpty()) {
			return areas;
		}

		// The lines each unit runs on each area: those of the depots whose sidings' paths run on it
		final Map<SharedAreas.Rail, Integer> areaOfRail = new HashMap<>();
		for (int i = 0; i < found.size(); i++) {
			final int index = i;
			found.get(i).rails.forEach(rail -> areaOfRail.put(rail, index));
		}
		final List<Map<String, Set<String>>> unitLines = new ArrayList<>();
		found.forEach(area -> unitLines.add(new TreeMap<>()));
		sidings.forEach(siding -> {
			final Depot depot = dataCache.sidingIdToDepot.get(siding.id);
			final Set<Long> unit = depot == null ? null : units.get(depot.id);
			if (unit == null || depot.transportMode.continuousMovement) {
				return;
			}
			final Set<Integer> on = new TreeSet<>();
			siding.forEachPathNodes(nodes -> {
				for (int i = 1; i < nodes.length; i++) {
					final Integer index = areaOfRail.get(new SharedAreas.Rail(nodes[i - 1], nodes[i]));
					if (index != null) {
						on.add(index);
					}
				}
			});
			final Set<String> lines = LineDuties.linesOf(depot, dataCache);
			on.forEach(index -> unitLines.get(index).computeIfAbsent(unit.toString(), key -> new TreeSet<>()).addAll(lines));
		});

		final Map<UUID, Platform> platformsByRail = new HashMap<>();
		platforms.forEach(platform -> {
			final List<BlockPos> positions = platform.getOrderedPositions(BlockPos.ZERO, false);
			platformsByRail.put(RailKeys.of(positions.get(0).asLong(), positions.get(1).asLong()), platform);
		});
		for (int i = 0; i < found.size(); i++) {
			final SharedAreas.Area sharedArea = found.get(i);
			final Map<String, Set<String>> linesOnArea = unitLines.get(i);
			final Area area = new Area();
			sharedArea.units.forEach(unit -> {
				final Unit eachUnit = new Unit();
				eachUnit.depotIds.addAll(parseDepotIds(unit));
				eachUnit.lines.addAll(linesOnArea.getOrDefault(unit, Collections.emptySet()));
				area.units.add(eachUnit);
			});
			for (final SharedAreas.Rail rail : sharedArea.rails) {
				final Map<BlockPos, Rail> from = rails.get(BlockPos.of(rail.node1));
				final Rail track = from == null ? null : from.get(BlockPos.of(rail.node2));
				if (track != null) {
					area.length += track.getLength();
				}
				final Platform platform = platformsByRail.get(RailKeys.of(rail.node1, rail.node2));
				final Station station = platform == null ? null : dataCache.platformIdToStation.get(platform.id);
				if (station != null) {
					area.stationIds.add(station.id);
				}
			}
			area.start = BlockPos.of(sharedArea.rails.get(0).node1);
			area.key = sharedArea.describe();
			areas.add(area);
		}
		return areas;
	}

	private static Set<Long> parseDepotIds(String unit) {
		final Set<Long> depotIds = new TreeSet<>();
		for (final String part : unit.replace("[", "").replace("]", "").split(",")) {
			if (!part.trim().isEmpty()) {
				depotIds.add(Long.parseLong(part.trim()));
			}
		}
		return depotIds;
	}

	/**
	 * Track run on by more than one planning unit.
	 */
	public static final class Area {

		/**
		 * The units on it, in order.
		 */
		public final List<Unit> units = new ArrayList<>();
		/**
		 * The stations with platforms on it, in order.
		 */
		public final Set<Long> stationIds = new TreeSet<>();
		/**
		 * Its length, in blocks.
		 */
		public double length;
		/**
		 * Where its lowest rail starts, for an area with no platforms.
		 */
		public BlockPos start;
		/**
		 * The same for the same area, to tell when areas change.
		 */
		public String key;
	}

	/**
	 * A planning unit on a shared area: its depots, and the operating lines they run there.
	 */
	public static final class Unit {

		public final Set<Long> depotIds = new TreeSet<>();
		public final Set<String> lines = new TreeSet<>();
	}
}
