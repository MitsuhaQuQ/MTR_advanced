package transitsim.fare;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * What one trip costs, from entering at one gate to leaving at another (design document 6.13). A gate either belongs to
 * one group, or is shared by every group of its station:
 * <ul>
 * <li>leaving at a gate of one group, that group is the trip's last;</li>
 * <li>leaving at a shared gate, the fare path crossing the fewest groups decides;</li>
 * <li>shared gates at both ends: the cheapest of the groups that could be meant.</li>
 * </ul>
 * Each part of the path is charged by its own group's tiers, capped by its own cap, and the parts are added up.
 * Entering and leaving at the same station costs the group's start fare, or its overtime fine.
 */
public final class FareTrip {

	private FareTrip() {
	}

	/**
	 * A gate as fares go by it: its station and the groups it may stand for.
	 */
	public static final class Gate {

		public final long station;
		public final Set<String> groups;
		public final boolean shared;

		public Gate(long station, Set<String> groups, boolean shared) {
			this.station = station;
			this.groups = Collections.unmodifiableSet(new LinkedHashSet<>(groups));
			this.shared = shared;
		}
	}

	public static final class Result {

		/**
		 * The fare, or -1 if the stations aren't joined.
		 */
		public final int fare;
		/**
		 * The path charged, or null at the same station or if there is none.
		 */
		public final FareNetwork.Path path;
		/**
		 * The group charged at the same station, or the last group of the path.
		 */
		public final String group;
		public final boolean overtime;

		Result(int fare, FareNetwork.Path path, String group, boolean overtime) {
			this.fare = fare;
			this.path = path;
			this.group = group;
			this.overtime = overtime;
		}
	}

	/**
	 * @param levels        each group's fares; a group without any is charged by the defaults
	 * @param minutesInside how long from entering to leaving
	 */
	public static Result charge(FareNetwork network, Function<String, FareLevels> levels, Gate entry, Gate exit, long minutesInside) {
		final Function<String, FareLevels> levelsOf = group -> {
			final FareLevels found = group == null ? null : levels.apply(group);
			return found == null ? new FareLevels() : found;
		};
		if (entry.station == exit.station) {
			return sameStation(levelsOf, entry, exit, minutesInside);
		}

		Result best = null;
		int bestCrossings = Integer.MAX_VALUE;
		long bestBlocks = Long.MAX_VALUE;
		final Set<String> entryGroups = entry.groups.isEmpty() ? Collections.singleton(null) : entry.groups;
		final Set<String> exitGroups = exit.groups.isEmpty() ? Collections.singleton(null) : exit.groups;
		// One search from each group entered in serves every group left by (2026-11-12)
		final List<String> exitList = new ArrayList<>(exitGroups);
		final List<Set<String>> endGroupSets = new ArrayList<>();
		exitList.forEach(exitGroup -> endGroupSets.add(exitGroup == null ? Collections.emptySet() : Collections.singleton(exitGroup)));
		for (final String entryGroup : entryGroups) {
			final FareNetwork.Path[] paths = network.find(entry.station, exit.station, entryGroup == null ? Collections.emptySet() : Collections.singleton(entryGroup), endGroupSets);
			for (int exitIndex = 0; exitIndex < exitList.size(); exitIndex++) {
				final String exitGroup = exitList.get(exitIndex);
				final FareNetwork.Path path = paths[exitIndex];
				if (path == null) {
					continue;
				}
				int fare = 0;
				for (final FareNetwork.Part part : path.parts) {
					final FareLevels partLevels = levelsOf.apply(part.group);
					// The longest distance is only worked out for tiers made from the formula up to it
					fare += partLevels.getFare(part.blocks, partLevels.needsLongest() ? network.getLongestBlocks(part.group) : 0);
				}
				final String lastGroup = path.parts.isEmpty() ? exitGroup : path.parts.get(path.parts.size() - 1).group;
				final long blocks = path.getBlocks();
				final boolean better;
				if (best == null) {
					better = true;
				} else if (entry.shared && exit.shared) {
					// Shared at both ends: the cheapest of the groups that could be meant
					better = fare < best.fare || fare == best.fare && (path.crossings < bestCrossings || path.crossings == bestCrossings && blocks < bestBlocks);
				} else {
					// Otherwise the gate of one group and the path crossing the fewest groups decide
					better = path.crossings < bestCrossings || path.crossings == bestCrossings && (blocks < bestBlocks || blocks == bestBlocks && fare < best.fare);
				}
				if (better) {
					best = new Result(fare, path, lastGroup, false);
					bestCrossings = path.crossings;
					bestBlocks = blocks;
				}
			}
		}
		return best == null ? new Result(-1, null, null, false) : best;
	}

	private static Result sameStation(Function<String, FareLevels> levelsOf, Gate entry, Gate exit, long minutesInside) {
		// The exit gate's group, or the entry gate's, or the cheapest of the shared ones
		final Set<String> groups;
		if (!exit.shared && !exit.groups.isEmpty()) {
			groups = exit.groups;
		} else if (!entry.shared && !entry.groups.isEmpty()) {
			groups = entry.groups;
		} else {
			groups = new LinkedHashSet<>(exit.groups);
			groups.addAll(entry.groups);
		}
		String bestGroup = null;
		int bestFare = Integer.MAX_VALUE;
		boolean overtime = false;
		for (final String group : groups.isEmpty() ? Collections.<String>singleton(null) : groups) {
			final FareLevels groupLevels = levelsOf.apply(group);
			final int fare = groupLevels.getSameStationFare(minutesInside);
			if (fare < bestFare) {
				bestFare = fare;
				bestGroup = group;
				overtime = groupLevels.overtime && minutesInside > groupLevels.overtimeMinutes;
			}
		}
		return new Result(bestFare, null, bestGroup, overtime);
	}

	/**
	 * @return the fare of a trip, from a map of levels
	 */
	public static Result charge(FareNetwork network, Map<String, FareLevels> levels, Gate entry, Gate exit, long minutesInside) {
		return charge(network, levels::get, entry, exit, minutesInside);
	}
}
