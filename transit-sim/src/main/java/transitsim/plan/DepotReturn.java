package transitsim.plan;

import java.util.List;

/**
 * The way from the last stop of a route on the main path to the next route, while paths are generated. A train going back
 * to the depot after this route turns back on it as usual, and heads for the depot from there.
 */
public class DepotReturn<T extends PlanTrack, P> {

	public final int routeIndex;
	public final int start;
	public final List<T> way;
	public final P lastPlatform;
	private int stopRail;

	/**
	 * @param routeIndex   the route of the depot this is after
	 * @param start        the rail of the main path where the way starts
	 * @param way          the way the main path takes from the last stop of the route
	 * @param lastPlatform the last stop of the route
	 */
	public DepotReturn(int routeIndex, int start, List<T> way, P lastPlatform) {
		this.routeIndex = routeIndex;
		this.start = start;
		this.way = way;
		this.lastPlatform = lastPlatform;
	}

	public void findStop(List<? extends PlanTrack> mainPath) {
		stopRail = Math.max(start - 1, 0);
		while (stopRail > 0 && mainPath.get(stopRail).getDwellTime() == 0) {
			stopRail--;
		}
	}

	/**
	 * @return the rail of the main path where the train stops before this way
	 */
	public int getStopRail() {
		return stopRail;
	}

	/**
	 * @return the rail of {@link #way} at whose end a train on this way turns back, or -1 if it doesn't
	 */
	public int getTurnBackRail() {
		for (int i = 1; i < way.size() - 1; i++) {
			if (way.get(i).isOppositeOf(way.get(i + 1))) {
				return i;
			}
		}
		return -1;
	}
}
