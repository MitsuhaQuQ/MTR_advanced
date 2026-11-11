package transitsim.plan;

import java.util.List;

/** Stop occurrences, not unique platform names: a closing loop visit must remain distinct from its first visit. */
public final class AnnouncementStops {
	private AnnouncementStops() { }
	public static int nearest(int hint, long platform, List<long[]> routes, boolean[] sharedEnd) {
		if (platform == 0) return hint;
		int base = 0, best = hint;
		long distance = Long.MAX_VALUE;
		for (int r=0;r<routes.size();r++) {
			long[] stops = routes.get(r);
			for (int i=0;i<stops.length;i++) if (stops[i] == platform) {
				int index = base + i;
				long d = Math.abs((long)index - hint);
				if (d < distance) { best = index; distance = d; }
			}
			base += stops.length;
			if (r < sharedEnd.length && sharedEnd[r]) base--;
		}
		return best;
	}
	/** The out-of-depot lead has no passenger stop number; count visits from the duty's first service rail. */
	public static void numberDuty(List<? extends PlanTrack> path, int firstServiceRail) {
		int count = 0;
		long previous = 0;
		for (int i=0;i<path.size();i++) {
			PlanTrack track = path.get(i);
			long platform = i >= firstServiceRail && track.isPlatformTrack() ? track.getStopId() : 0;
			if (platform != 0 && platform != previous) count++;
			track.setStopIndex(count);
			previous = platform;
		}
	}

	/**
	 * The next stop a train will stop at, from its own path (0.65.27): the first platform ahead of the head with a stop time,
	 * leaving out the platform the head is on. A stop the route lists that this train passes, as a service's exception
	 * or a dispatcher's skip has it, has no stop time on the train's path, so it isn't announced as the next station.
	 *
	 * @return the stop ID, or 0 if there is none ahead on the path
	 */
	public static long nextStop(List<? extends PlanTrack> path, int head) {
		if (head < 0 || head >= path.size()) return 0;
		final long current = path.get(head).isPlatformTrack() ? path.get(head).getStopId() : 0;
		for (int i = head + 1; i < path.size(); i++) {
			final PlanTrack track = path.get(i);
			final long stopId = track.isPlatformTrack() ? track.getStopId() : 0;
			if (stopId != 0 && stopId != current && track.getDwellTime() > 0) return stopId;
		}
		return 0;
	}
}
