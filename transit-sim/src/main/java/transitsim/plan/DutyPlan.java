package transitsim.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a depot worked out for the path of a duty, shared by its sidings: the duty's segments joined up from its first stop
 * to its last, with what the plans of a path need to know about the routes it runs. Each siding adds its own way out and
 * back (design document 6.12, block E2).
 */
public class DutyPlan<T extends PlanTrack, P> {

	public final String key;
	public final List<Long> routeIds;
	public final Map<Long, Integer> cutStops;
	public final Map<Long, Integer> entryStops;
	public final Map<Long, List<T>> entryLeads;
	/**
	 * From the first stop of the duty to its last.
	 */
	public final List<T> body;
	/**
	 * The rail of {@link #body} at the first stop of each segment.
	 */
	public final int[] segmentStarts;
	public final P firstPlatform;
	public final P lastPlatform;
	public final boolean startsOnLine;
	public final boolean staysOnLine;
	/**
	 * Where the duty can turn back more than one way, each from a stop on {@link #body} (the section's start is that stop's
	 * rail, and each way starts on it) to the first stop of the next segment.
	 */
	public final List<TurnBackSection<T>> turnBackSections = new ArrayList<>();
	/**
	 * By segment, the last stop of its route it runs to, where that isn't the route's last (D4). A route can be in several
	 * segments of a duty, each cut and joined at its own stops, which {@link #cutStops} by route can't tell apart.
	 */
	public final Map<Integer, Integer> segmentCuts = new java.util.TreeMap<>();
	/**
	 * By segment, the stop of its route where it joins it, where that isn't the route's first (D4).
	 */
	public final Map<Integer, Integer> segmentEntries = new java.util.TreeMap<>();
	/**
	 * Whether {@link #segmentCuts} and {@link #segmentEntries} are filled in, so the plans go by them rather than by route.
	 */
	public boolean segmentsKnown;

	public DutyPlan(String key, List<Long> routeIds, Map<Long, Integer> cutStops, Map<Long, Integer> entryStops, Map<Long, List<T>> entryLeads, List<T> body, int[] segmentStarts, P firstPlatform, P lastPlatform, boolean startsOnLine, boolean staysOnLine) {
		this.key = key;
		this.routeIds = routeIds;
		this.cutStops = cutStops;
		this.entryStops = entryStops;
		this.entryLeads = entryLeads;
		this.body = body;
		this.segmentStarts = segmentStarts;
		this.firstPlatform = firstPlatform;
		this.lastPlatform = lastPlatform;
		this.startsOnLine = startsOnLine;
		this.staysOnLine = staysOnLine;
	}
}
