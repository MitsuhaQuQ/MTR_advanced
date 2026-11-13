package transitsim.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Joins up a duty's segments into its whole path: each service from where it joins its route to where it leaves, then on
 * to the next segment the way the depot's own path would turn back. Moved out of the game's depot path building
 * (2026-11-14, step 2d of splitting the depot): the host finds each segment's path and the ways between two platforms;
 * how they are joined, where a segment starts, which ways round are kept and what is noted for cut and joined routes are
 * here.
 */
public final class DutyAssembly {

	private DutyAssembly() {
	}

	/**
	 * What the host makes of one segment of the duty.
	 *
	 * @param <T> the host's track
	 * @param <P> the host's platform
	 */
	public static final class Part<T extends PlanTrack, P> {

		public final long routeId;
		/**
		 * The stops of the route the segment runs, and how many the route has.
		 */
		public final int firstStop;
		public final int lastStop;
		public final int routeStops;
		/**
		 * The segment's path from its first stop to its last, which isn't changed.
		 */
		public final List<T> template;
		/**
		 * The platforms it starts and ends at, and their ids.
		 */
		public final P firstPlatform;
		public final P lastPlatform;
		public final long firstPlatformId;
		public final long lastPlatformId;
		/**
		 * How long a train stops at the first platform.
		 */
		public final int firstDwellTime;

		public Part(long routeId, int firstStop, int lastStop, int routeStops, List<T> template, P firstPlatform, long firstPlatformId, P lastPlatform, long lastPlatformId, int firstDwellTime) {
			this.routeId = routeId;
			this.firstStop = firstStop;
			this.lastStop = lastStop;
			this.routeStops = routeStops;
			this.template = template;
			this.firstPlatform = firstPlatform;
			this.firstPlatformId = firstPlatformId;
			this.lastPlatform = lastPlatform;
			this.lastPlatformId = lastPlatformId;
			this.firstDwellTime = firstDwellTime;
		}

		public boolean runsToEnd() {
			return lastStop == routeStops - 1;
		}
	}

	/**
	 * How the host's tracks compare and change.
	 */
	public interface Tracks<T extends PlanTrack> {

		boolean sameRail(T track, T other);

		/**
		 * @return whether the two are the same rail run the other way
		 */
		boolean oppositeRail(T track, T other);

		/**
		 * @return a copy of the track with this stop and stop time, the original unchanged
		 */
		T withStop(T track, long stopId, int dwellTime);
	}

	/**
	 * What the host finds.
	 */
	public interface Host<T extends PlanTrack, P> {

		/**
		 * @return segment {@code k}, or null if its route, a platform or its path can't be found
		 */
		Part<T, P> part(int k);

		/**
		 * Finds the way from the last platform of one segment to the first of the next into {@code connectingPath}, left
		 * empty if there is none.
		 *
		 * @param routeBeforeId the route of the segment before when that one ran to its end, so it may turn back the way the
		 *                      route lists, else 0
		 * @return the ways round from the stop, if there are any to choose from
		 */
		TurnBackSection<T> connect(List<T> connectingPath, Part<T, P> before, Part<T, P> after, long routeBeforeId);

		/**
		 * Adds the ways a route turning back in front of its last station (站前折返) may take into the next one; called where
		 * a segment that ran to its end is followed by one starting at its route's first stop.
		 *
		 * @param previousStart the rail of the body where the segment before starts
		 * @param nextStart     the rail of the body where this one starts
		 */
		void frontTurnBack(List<T> body, Part<T, P> before, Part<T, P> after, int previousStart, int nextStart, List<TurnBackSection<T>> turnBackSections);

		/**
		 * @return the path from the route's first stop to the stop a segment joins it at, which the route's plan is timed
		 * from, or null if there is none
		 */
		List<T> entryLead(Part<T, P> part);
	}

	/**
	 * @return the duty's plan, or null if a part of it can't be found
	 */
	public static <T extends PlanTrack, P> DutyPlan<T, P> assemble(String key, int segmentCount, boolean startsOnLine, boolean staysOnLine, Host<T, P> host, Tracks<T> tracks) {
		final List<T> body = new JoinedPath<>();
		final Set<Integer> indexBoundaries = new TreeSet<>();
		indexBoundaries.add(0);
		final int[] segmentStarts = new int[segmentCount];
		final List<Long> routeIds = new ArrayList<>();
		final Map<Long, Integer> cutStops = new HashMap<>();
		final Map<Long, Integer> entryStops = new HashMap<>();
		final Map<Long, List<T>> entryLeads = new HashMap<>();
		// Where each segment is cut and joined, by segment, as a route can be in several (D4)
		final Map<Integer, Integer> segmentCuts = new HashMap<>();
		final Map<Integer, Integer> segmentEntries = new HashMap<>();
		final List<TurnBackSection<T>> turnBackSections = new ArrayList<>();
		Part<T, P> first = null;
		Part<T, P> previous = null;
		int previousStart = 0;

		for (int k = 0; k < segmentCount; k++) {
			final Part<T, P> part = host.part(k);
			if (part == null) {
				return null;
			}
			final List<T> segmentPath = new JoinedPath<>(part.template);

			if (previous != null && previous.lastPlatformId != part.firstPlatformId) {
				final List<T> connectingPath = new ArrayList<>();
				final TurnBackSection<T> turnBackSection = host.connect(connectingPath, previous, part, previous.runsToEnd() ? previous.routeId : 0);
				if (connectingPath.isEmpty()) {
					return null;
				}
				// More than one way round, each from the last stop, which is already the last rail of the body
				if (turnBackSection != null && turnBackSection.ways.size() > 1 && !body.isEmpty() && tracks.sameRail(body.get(body.size() - 1), connectingPath.get(0))) {
					turnBackSections.add(new TurnBackSection<>(body.size() - 1, turnBackSection.ways, turnBackSection.defaultWay));
				}
				indexBoundaries.add(body.size());
				append(body, connectingPath, tracks);
				indexBoundaries.add(Math.max(0, body.size() - 1));
			}

			final T start = tracks.withStop(segmentPath.get(0), segmentPath.get(0).getStopId(), segmentPath.get(0).getDwellTime());
			segmentPath.set(0, start);
			final T last = body.isEmpty() ? null : body.get(body.size() - 1);
			if (last != null && tracks.sameRail(start, last)) {
				segmentStarts[k] = body.size() - 1;
				// Come from another platform to this route's first stop, the train stops there for its departure: the first
				// stop of a route's own path has no stop time, and without the way there it would run straight through
				final boolean arrivedHere = k > 0 && start.getStopId() != 0 && start.getDwellTime() == 0 && last.getStopId() == start.getStopId();
				final int stopDwell = Math.max(last.getDwellTime(), part.firstDwellTime);
				body.set(body.size() - 1, arrivedHere && stopDwell > 0 ? tracks.withStop(start, start.getStopId(), stopDwell) : start);
				body.addAll(segmentPath.subList(1, segmentPath.size()));
			} else if (last != null && tracks.oppositeRail(start, last)) {
				// Turning back on the platform: the stop is the arrival there, and the platform reversed is only the way out
				segmentPath.set(0, tracks.withStop(start, 0, 0));
				segmentStarts[k] = body.size() - 1;
				body.addAll(segmentPath);
			} else {
				segmentStarts[k] = body.size();
				body.addAll(segmentPath);
			}

			// The route before may turn back at other platforms of its last station (站前折返)
			if (k > 0 && previous.runsToEnd() && part.firstStop == 0) {
				host.frontTurnBack(body, previous, part, previousStart, segmentStarts[k], turnBackSections);
			}

			if (first == null) {
				first = part;
			}
			routeIds.add(part.routeId);
			if (part.lastStop < part.routeStops - 1) {
				cutStops.putIfAbsent(part.routeId, part.lastStop);
				segmentCuts.put(k, part.lastStop);
			}
			if (part.firstStop > 0) {
				segmentEntries.put(k, part.firstStop);
			}
			if (part.firstStop > 0 && !entryStops.containsKey(part.routeId)) {
				// The plan of a route joined part of the way along counts from its first stop, so the way there is timed too
				final List<T> leadPath = host.entryLead(part);
				if (leadPath != null) {
					entryStops.put(part.routeId, part.firstStop);
					entryLeads.put(part.routeId, leadPath);
				}
			}
			previous = part;
			previousStart = segmentStarts[k];
		}
		final DutyPlan<T, P> dutyPlan = new DutyPlan<>(key, routeIds, cutStops, entryStops, entryLeads, body, segmentStarts, first == null ? null : first.firstPlatform, previous == null ? null : previous.lastPlatform, startsOnLine, staysOnLine);
		for (int start : segmentStarts) indexBoundaries.add(start);
		indexBoundaries.add(body.size());
		dutyPlan.indexBoundaries = indexBoundaries.stream().mapToInt(Integer::intValue).toArray();
		dutyPlan.turnBackSections.addAll(turnBackSections);
		dutyPlan.segmentCuts.putAll(segmentCuts);
		dutyPlan.segmentEntries.putAll(segmentEntries);
		dutyPlan.segmentsKnown = true;
		return dutyPlan;
	}

	/**
	 * Adds a way to the end of a path, leaving out its first rail when the path already ends on it, in one piece so a
	 * {@link JoinedPath} keeps it as one.
	 */
	static <T extends PlanTrack> void append(List<T> path, List<T> way, Tracks<T> tracks) {
		if (way.isEmpty()) {
			path.clear();
		} else {
			final boolean sameFirstRail = !path.isEmpty() && tracks.sameRail(path.get(path.size() - 1), way.get(0));
			path.addAll(sameFirstRail ? way.subList(1, way.size()) : way);
		}
	}
}
