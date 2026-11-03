package transitsim.plan;

import java.util.List;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;

/**
 * Where a service can leave its route for another one, or join a route, part of the way along. Like the boundary stations
 * of through running in Japan, this is only at platforms marked as timetable boundaries
 * (the host's platform setting, given here as {@code isBoundaryPlatform}), where a service also has to stop. Without boundaries a service can only carry on
 * from the last stop of its route onto the first stop of the next.
 * <p>
 * Loops could already be left and joined at any stop they stop at before boundaries existed, and still can, so saves that
 * use it keep working.
 */
public interface TimetableBoundaries {

	/**
	 * @return true if a service can leave or join this route at this stop
	 */
	static boolean isBoundaryStop(PlanRoute route, int stop, LongPredicate isBoundaryPlatform) {
		final List<? extends PlanStop> stops = route.getStops();
		if (stop <= 0 || stop >= stops.size() - 1) {
			return false;
		}
		final PlanStop routePlatform = stops.get(stop);
		if (routePlatform.isPass()) {
			return false;
		}
		if (route.isLoop()) {
			return true;
		}
		return isBoundaryPlatform.test(routePlatform.getPlatformId());
	}

	/**
	 * @return true if the route has any stop where a service can leave or join it part of the way along
	 */
	static boolean hasBoundaryStops(PlanRoute route, LongPredicate isBoundaryPlatform) {
		for (int i = 1; i < route.getStops().size() - 1; i++) {
			if (isBoundaryStop(route, i, isBoundaryPlatform)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return why a service of this route can't carry on like this, or null if it can
	 */
	static String checkLink(PlanRoute route, RouteTimetable.ServiceLink link, LongFunction<? extends PlanRoute> getRoute, LongPredicate isBoundaryPlatform) {
		final PlanRoute nextRoute = getRoute.apply(link.routeId);
		if (nextRoute == null || nextRoute.getStops().isEmpty()) {
			return "直通到的线路不存在";
		}
		if (nextRoute.getTransportModeId() != route.getTransportModeId()) {
			return "直通到的线路运输方式不同";
		}
		if (link.fromStop >= 0 && link.fromStop < route.getStops().size() - 1 && !isBoundaryStop(route, link.fromStop, isBoundaryPlatform)) {
			return "只能在标了时刻边界、并且停车的站台接出";
		}
		if (link.toStop > 0 && !isBoundaryStop(nextRoute, link.toStop, isBoundaryPlatform)) {
			return "只能在标了时刻边界、并且停车的站台接入";
		}
		return null;
	}

	/**
	 * Drops links that break the rules, which only a client not checking them sends.
	 *
	 * @return true if any were dropped
	 */
	static boolean sanitizeLinks(PlanRoute route, LongFunction<? extends PlanRoute> getRoute, LongPredicate isBoundaryPlatform) {
		boolean changed = false;
		for (final RouteTimetable table : route.getTimetable().getTables()) {
			changed |= table.links.values().removeIf(link -> checkLink(route, link, getRoute, isBoundaryPlatform) != null);
		}
		return changed;
	}
}
