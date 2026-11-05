package transitsim.sim;

import transitsim.plan.PlanRoute;
import transitsim.plan.PlanStop;
import transitsim.plan.RouteTimetable;

import java.util.ArrayList;
import java.util.List;

/**
 * A route of stops in order with its timetable, for running services without a world. Its stops have no times of their
 * own, so trains run at the fastest they can.
 */
class TestRoute implements PlanRoute {

	final long id;
	final RouteTimetable timetable = new RouteTimetable();
	final List<Stop> platformIds = new ArrayList<>();
	/**
	 * See {@link PlanRoute#getTerminalPlatforms}.
	 */
	final List<Long> terminalPlatforms = new ArrayList<>();

	TestRoute(long id, long... platformIds) {
		this.id = id;
		for (final long platformId : platformIds) {
			this.platformIds.add(new Stop(platformId));
		}
	}

	@Override
	public long getRouteId() {
		return id;
	}

	@Override
	public RouteTimetable getTimetable() {
		return timetable;
	}

	@Override
	public List<Stop> getStops() {
		return platformIds;
	}

	@Override
	public boolean isLoop() {
		return platformIds.size() > 2 && platformIds.get(0).platformId == getLastPlatformId();
	}

	@Override
	public int getTransportModeId() {
		return 0;
	}

	@Override
	public List<Long> getTerminalPlatforms() {
		return terminalPlatforms;
	}

	static class Stop implements PlanStop {

		final long platformId;

		Stop(long platformId) {
			this.platformId = platformId;
		}

		@Override
		public long getPlatformId() {
			return platformId;
		}

		@Override
		public boolean isPass() {
			return false;
		}

		@Override
		public int getArrival(int table) {
			return -1;
		}

		@Override
		public int getDeparture(int table) {
			return -1;
		}

		@Override
		public boolean getOvertake(int table) {
			return false;
		}
	}
}
