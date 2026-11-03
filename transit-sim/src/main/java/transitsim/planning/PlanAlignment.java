package transitsim.planning;

import transitsim.planning.OperationPlan.Duty;
import transitsim.planning.OperationPlan.Phase;
import transitsim.planning.OperationPlan.PhaseType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out where every train should be at a given time, for restarting a server without catching up.
 * Trains already furthest along take their planned place first. A train whose place is taken waits at the nearest
 * earlier free stop of its duty and runs from there as a late train. No alignment triggers cut-offs or timeouts.
 */
public final class PlanAlignment {

	private PlanAlignment() {
	}

	public static List<Placement> align(OperationPlan plan, long epochWeekOffset, long minHeadwayMillis) {
		final long timeMillis = OperationTime.normalize(epochWeekOffset);
		final List<Placement> wanted = new ArrayList<>();
		for (final Duty duty : plan.duties) {
			for (int i = 0; i < duty.phases.size(); i++) {
				final Phase phase = duty.phases.get(i);
				for (int week = 0; week <= 1; week++) {
					final long shiftedMillis = timeMillis + week * OperationTime.WEEK_MILLIS;
					if (phase.startMillis <= shiftedMillis && shiftedMillis < phase.endMillis) {
						wanted.add(new Placement(duty.index, i, phase, shiftedMillis - phase.startMillis, 0));
					}
				}
			}
		}
		wanted.sort(Comparator.comparingLong((Placement placement) -> -placement.elapsedMillis).thenComparingInt(placement -> placement.dutyIndex));

		final List<Placement> placed = new ArrayList<>();
		final Set<Long> occupiedPlatforms = new HashSet<>();
		final Map<String, List<Long>> sectionEntries = new HashMap<>();
		wanted.forEach(placement -> {
			if (claim(placement.phase, occupiedPlatforms, sectionEntries, minHeadwayMillis)) {
				placed.add(placement);
			} else {
				placed.add(holdEarlier(plan.duties.get(placement.dutyIndex), placement, occupiedPlatforms));
			}
		});
		return placed;
	}

	private static Placement holdEarlier(Duty duty, Placement placement, Set<Long> occupiedPlatforms) {
		final long nowMillis = placement.phase.startMillis + placement.elapsedMillis;
		for (int i = placement.phaseIndex - 1; i >= 0; i--) {
			final Phase phase = duty.phases.get(i);
			if (phase.type == PhaseType.STOP && occupiedPlatforms.add(phase.fromPlatform)) {
				return new Placement(duty.index, i, phase, phase.endMillis - phase.startMillis, nowMillis - phase.endMillis);
			}
		}
		return new Placement(duty.index, Placement.UNPLACED, null, 0, 0);
	}

	/**
	 * A platform holds one train. A section holds any number at least the minimum headway apart, which stands in for
	 * blocks until track data is available. Depot moves share nothing, and each direction of a section is separate.
	 */
	private static boolean claim(Phase phase, Set<Long> occupiedPlatforms, Map<String, List<Long>> sectionEntries, long minHeadwayMillis) {
		switch (phase.type) {
			case STOP:
			case LAYOVER:
				return occupiedPlatforms.add(phase.fromPlatform);
			case RUN:
			case CONNECT:
				final List<Long> entries = sectionEntries.computeIfAbsent(phase.fromPlatform + ">" + phase.toPlatform, key -> new ArrayList<>());
				if (entries.stream().anyMatch(entry -> Math.abs(entry - phase.startMillis) < minHeadwayMillis)) {
					return false;
				}
				entries.add(phase.startMillis);
				return true;
			default:
				return true;
		}
	}

	public static final class Placement {

		public static final int UNPLACED = -1;

		public final int dutyIndex;
		public final int phaseIndex;
		public final Phase phase;
		public final long elapsedMillis;
		public final long heldLateMillis;

		private Placement(int dutyIndex, int phaseIndex, Phase phase, long elapsedMillis, long heldLateMillis) {
			this.dutyIndex = dutyIndex;
			this.phaseIndex = phaseIndex;
			this.phase = phase;
			this.elapsedMillis = elapsedMillis;
			this.heldLateMillis = heldLateMillis;
		}

		public boolean isHeld() {
			return heldLateMillis > 0 || phaseIndex == UNPLACED;
		}
	}
}
