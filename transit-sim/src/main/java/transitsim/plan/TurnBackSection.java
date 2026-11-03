package transitsim.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a route ends on the usual path of a depot and a train can turn back on more than one rail before the next route.
 */
public class TurnBackSection<T extends PlanTrack> {

	public final int start;
	public final List<List<T>> ways;
	public final int defaultWay;
	private int stopRail;

	/**
	 * @param start      the rail of the main path where the way from the stop starts
	 * @param ways       for each turn back rail in order, the way from the stop to the next one, or null if it can't be used
	 * @param defaultWay the way the main path takes
	 */
	public TurnBackSection(int start, List<List<T>> ways, int defaultWay) {
		this.start = start;
		this.ways = ways;
		this.defaultWay = defaultWay;
	}

	/**
	 * @return the rail of the main path where the train stops before this section
	 */
	public int getStopRail() {
		return stopRail;
	}

	public List<T> getDefaultWay() {
		return ways.get(defaultWay);
	}

	/**
	 * Numbers the stops of the other ways round like the main path, which must be numbered already.
	 */
	public void numberStops(List<T> mainPath) {
		stopRail = Math.max(start - 1, 0);
		while (stopRail > 0 && mainPath.get(stopRail).getDwellTime() == 0) {
			stopRail--;
		}
		for (int i = 0; i < ways.size(); i++) {
			final List<T> way = ways.get(i);
			if (way != null && i != defaultWay) {
				T previous = start > 0 ? mainPath.get(start - 1) : way.get(0);
				int stopIndex = previous.getStopIndex();
				for (final T pathData : way) {
					if (pathData.isPlatformTrack() && pathData.getStopId() != 0 && !previous.isPlatformTrack()) {
						stopIndex++;
					}
					pathData.setStopIndex(stopIndex);
					previous = pathData;
				}
			}
		}
	}

	/**
	 * The way that ends a lap ends at the first stop, so its last rail counts as that stop.
	 *
	 * @param mainSize the number of rails of the main path
	 */
	public void setEndStopIndex(int mainSize, int stopIndex) {
		if (getEnd() == mainSize - 1) {
			ways.forEach(way -> {
				if (way != null) {
					way.get(way.size() - 1).setStopIndex(stopIndex);
				}
			});
		}
	}

	public int getLength() {
		return getDefaultWay().size();
	}

	/**
	 * @return the last rail of this section on the main path
	 */
	public int getEnd() {
		return start + getLength() - 1;
	}

	public List<T> getMainPath(List<T> mainPath, int way) {
		final List<T> newMainPath = new ArrayList<>(mainPath.subList(0, start));
		newMainPath.addAll(ways.get(way));
		newMainPath.addAll(mainPath.subList(start + getLength(), mainPath.size()));
		return newMainPath;
	}
}
