package transitsim.plan;

import transitsim.motion.SpeedCurves;
import transitsim.motion.SpeedProfile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The times along a path a train runs at the fastest on its speed curves, which its plans start from (S4a). The game's
 * sidings and the component's simulators work them out the same way.
 */
public final class PathTimes {

	private PathTimes() {
	}

	/**
	 * @return true if a train runs through this platform without stopping, coming onto it from another one
	 */
	public static boolean isPassingPlatform(List<? extends PlanTrack> path, int index) {
		final PlanTrack track = path.get(index);
		return index > 0 && track.getDwellTime() == 0 && track.getStopId() != 0 && track.isPlatformTrack() && path.get(index - 1).getStopId() != track.getStopId();
	}

	/**
	 * Times a path with speed curves (design document section 8), so the times are the ones trains on the curves run to.
	 * Gives the same kinds of results as the step by step timing: a time segment for each part with one acceleration,
	 * ending at each stop and each platform passed, the time of each stop, and the times between platforms.
	 */
	public static void curveTimes(List<? extends PlanTrack> path, SpeedCurves curves, List<TimeSegment> timeSegments, Map<Long, Map<Long, Float>> platformTimes, StopTimes stopTimes) {
		final double[] railEnds = new double[path.size()];
		double distance = 0;
		for (int i = 0; i < path.size(); i++) {
			distance += path.get(i).getTrackLength();
			railEnds[i] = distance;
		}
		final double totalDistance = distance;

		// When each run leaves, after the stops before it
		final double[] runStarts = new double[curves.runs.size()];
		double ticks = 0;
		for (int i = 0; i < curves.runs.size(); i++) {
			runStarts[i] = ticks;
			final SpeedCurves.Run run = curves.runs.get(i);
			ticks += run.fastest.getDuration() + path.get(run.stopRailIndex).getDwellTime() * 10;
		}

		int runIndex = 0;
		for (final SpeedCurves.Run run : curves.runs) {
			// Segments end where the train stops or passes a platform, so arrivals can be read off them
			final TreeMap<Double, Integer> ends = new TreeMap<>();
			int first = java.util.Arrays.binarySearch(railEnds, run.fastest.startPosition);
			first = first >= 0 ? first + 1 : -first - 1;
			for (int i = first; i <= run.stopRailIndex; i++) {
				// A stop ends where the train stands, which may be short of the end of its rail (round 4)
				final double position = i == run.stopRailIndex ? run.fastest.endPosition : railEnds[i];
				if (position > run.fastest.startPosition && position <= run.fastest.endPosition && (isPassingPlatform(path, i) || i == run.stopRailIndex)) {
					ends.put(position, i);
				}
			}
			final List<double[]> pieces = run.fastest.getPieces();
			for (int p = 0; p < pieces.size(); p++) {
				final double[] piece = pieces.get(p);
				final double pieceEnd = p + 1 < pieces.size() ? pieces.get(p + 1)[0] : run.fastest.endPosition;
				double start = piece[0];
				for (final Map.Entry<Double, Integer> end : ends.subMap(piece[0], false, pieceEnd, true).entrySet()) {
					addCurveSegment(timeSegments, run.fastest, piece, start, end.getKey(), runStarts[runIndex], path, end.getValue(), totalDistance);
					start = end.getKey();
				}
				if (pieceEnd > start + 1E-6) {
					addCurveSegment(timeSegments, run.fastest, piece, start, pieceEnd, runStarts[runIndex], path, -1, totalDistance);
				}
			}
			runIndex++;
		}

		long savedRailBaseIdOld = 0;
		float timeOld = 0;
		runIndex = 0;
		for (int i = 0; i < path.size(); i++) {
			while (runIndex < curves.runs.size() - 1 && curves.runs.get(runIndex).stopRailIndex < i) {
				runIndex++;
			}
			final SpeedCurves.Run run = curves.runs.get(runIndex);
			final PlanTrack pathData = path.get(i);
			final float time = (float) (runStarts[runIndex] + run.fastest.getTime(i == run.stopRailIndex ? run.fastest.endPosition : railEnds[i]));
			final boolean isPlatformStop = pathData.isPlatformTrack() && pathData.getStopId() != 0;
			if (pathData.getDwellTime() > 0 || isPassingPlatform(path, i)) {
				final boolean isTurnBack = !isPlatformStop && i + 1 < path.size() && pathData.isOppositeOf(path.get(i + 1));
				stopTimes.add(i, isPlatformStop ? pathData.getStopId() : 0, isTurnBack ? pathData.getTrackKey() : null, time, time + pathData.getDwellTime() * 10, isPlatformStop && pathData.getDwellTime() > 0);
			}
			if (pathData.getStopId() != 0) {
				final float middle = time + pathData.getDwellTime() * 5;
				if (savedRailBaseIdOld != 0) {
					platformTimes.computeIfAbsent(savedRailBaseIdOld, key -> new HashMap<>()).put(pathData.getStopId(), middle - timeOld);
				}
				savedRailBaseIdOld = pathData.getStopId();
				timeOld = middle;
			}
		}
	}

	/**
	 * @param railIndex the rail whose end the segment ends at, or -1
	 */
	private static void addCurveSegment(List<TimeSegment> timeSegments, SpeedProfile profile, double[] piece, double start, double end, double runStart, List<? extends PlanTrack> path, int railIndex, double totalDistance) {
		final double acceleration = piece[3];
		final int speedChange = acceleration > 1E-9 ? 1 : acceleration < -1E-9 ? -1 : 0;
		final TimeSegment timeSegment = new TimeSegment(start, (float) profile.getSpeed(start), (float) (runStart + profile.getTime(start)), speedChange, (float) Math.abs(acceleration));
		timeSegment.endRailProgress = end;
		timeSegment.endTime = (float) (runStart + profile.getTime(end));
		if (railIndex >= 0) {
			final PlanTrack pathData = path.get(railIndex);
			timeSegment.pass = isPassingPlatform(path, railIndex);
			timeSegment.savedRailBaseId = timeSegment.pass || pathData.getDwellTime() > 0 && end < totalDistance ? pathData.getStopId() : 0;
		}
		timeSegments.add(timeSegment);
	}
}
