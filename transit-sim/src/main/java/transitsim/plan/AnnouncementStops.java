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
}
