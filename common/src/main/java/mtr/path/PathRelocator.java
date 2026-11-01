package mtr.path;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds where a running train belongs after its path is replaced. A path is a list of directed rails, and the same
 * rail can appear several times on circular or turning back paths, so the pass number is matched as well.
 */
public final class PathRelocator {

	private PathRelocator() {
	}

	/**
	 * @return the new position, or null when the rest of the old path shares no rail with the new one
	 */
	public static Position locate(List<String> oldRails, List<Double> oldDistances, List<String> newRails, List<Double> newDistances, double railProgress) {
		final int oldIndex = findIndex(oldDistances, railProgress);
		if (oldIndex < 0) {
			return null;
		}

		for (int i = oldIndex; i < oldRails.size(); i++) {
			final int newIndex = findPass(newRails, oldRails.get(i), countPasses(oldRails, i));
			if (newIndex >= 0) {
				final double newStart = newIndex == 0 ? 0 : newDistances.get(newIndex - 1);
				if (i > oldIndex) {
					return new Position(newIndex, newStart);
				}
				final double oldStart = i == 0 ? 0 : oldDistances.get(i - 1);
				final double oldLength = oldDistances.get(i) - oldStart;
				final double fraction = oldLength > 0 ? (railProgress - oldStart) / oldLength : 0;
				return new Position(newIndex, newStart + fraction * (newDistances.get(newIndex) - newStart));
			}
		}
		return null;
	}

	private static int findIndex(List<Double> distances, double railProgress) {
		for (int i = 0; i < distances.size(); i++) {
			if (railProgress <= distances.get(i)) {
				return i;
			}
		}
		return distances.size() - 1;
	}

	private static int countPasses(List<String> rails, int index) {
		int passes = 0;
		for (int i = 0; i < index; i++) {
			if (rails.get(i).equals(rails.get(index))) {
				passes++;
			}
		}
		return passes;
	}

	/**
	 * Falls back to the last pass when the new path passes this rail fewer times.
	 */
	private static int findPass(List<String> rails, String rail, int pass) {
		final List<Integer> indices = new ArrayList<>();
		for (int i = 0; i < rails.size(); i++) {
			if (rails.get(i).equals(rail)) {
				indices.add(i);
			}
		}
		return indices.isEmpty() ? -1 : indices.get(Math.min(pass, indices.size() - 1));
	}

	public static final class Position {

		public final int railIndex;
		public final double railProgress;

		private Position(int railIndex, double railProgress) {
			this.railIndex = railIndex;
			this.railProgress = railProgress;
		}
	}
}
