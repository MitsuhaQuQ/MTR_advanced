package mtr.advanced;

import mtr.data.Platform;
import mtr.data.Route;
import transitsim.plan.RouteTimetable;

import java.util.function.LongFunction;

/**
 * Where a service can leave its route for another one, or join a route, part of the way along. Like the boundary stations
 * of through running in Japan, this is only at platforms marked as timetable boundaries
 * ({@link Platform#timetableBoundary}), where a service also has to stop. Without boundaries a service can only carry on
 * from the last stop of its route onto the first stop of the next.
 * <p>
 * Loops could already be left and joined at any stop they stop at before boundaries existed, and still can, so saves that
 * use it keep working.
 */
public interface TimetableBoundaries {

	/**
	 * @return true if a service can leave or join this route at this stop
	 */
	static boolean isBoundaryStop(Route route, int stop, LongFunction<Platform> getPlatform) {
		if (stop <= 0 || stop >= route.platformIds.size() - 1) {
			return false;
		}
		final Route.RoutePlatform routePlatform = route.platformIds.get(stop);
		if (routePlatform.pass) {
			return false;
		}
		if (route.isLoop()) {
			return true;
		}
		final Platform platform = getPlatform.apply(routePlatform.platformId);
		return platform != null && platform.timetableBoundary;
	}

	/**
	 * @return true if the route has any stop where a service can leave or join it part of the way along
	 */
	static boolean hasBoundaryStops(Route route, LongFunction<Platform> getPlatform) {
		for (int i = 1; i < route.platformIds.size() - 1; i++) {
			if (isBoundaryStop(route, i, getPlatform)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @return why a service of this route can't carry on like this, or null if it can
	 */
	static String checkLink(Route route, RouteTimetable.ServiceLink link, LongFunction<Route> getRoute, LongFunction<Platform> getPlatform) {
		final Route nextRoute = getRoute.apply(link.routeId);
		if (nextRoute == null || nextRoute.platformIds.isEmpty()) {
			return "直通到的线路不存在";
		}
		if (nextRoute.transportMode != route.transportMode) {
			return "直通到的线路运输方式不同";
		}
		if (link.fromStop >= 0 && link.fromStop < route.platformIds.size() - 1 && !isBoundaryStop(route, link.fromStop, getPlatform)) {
			return "只能在标了时刻边界、并且停车的站台接出";
		}
		if (link.toStop > 0 && !isBoundaryStop(nextRoute, link.toStop, getPlatform)) {
			return "只能在标了时刻边界、并且停车的站台接入";
		}
		return null;
	}

	/**
	 * Drops links that break the rules, which only a client not checking them sends.
	 *
	 * @return true if any were dropped
	 */
	static boolean sanitizeLinks(Route route, LongFunction<Route> getRoute, LongFunction<Platform> getPlatform) {
		boolean changed = false;
		for (final RouteTimetable table : route.timetable.getTables()) {
			changed |= table.links.values().removeIf(link -> checkLink(route, link, getRoute, getPlatform) != null);
		}
		return changed;
	}
}
