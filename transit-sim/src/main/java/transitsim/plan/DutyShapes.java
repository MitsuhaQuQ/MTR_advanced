package transitsim.plan;

import java.util.List;
import java.util.function.LongFunction;

/**
 * The shape of a duty (design document 6.12, block E2): the routes it runs, where it joins and leaves each, which stops each
 * service stops at or passes, where it starts and whether it ends on the line. Duties with the same shape share one path,
 * and a single service's passing or stopping becomes real because it is part of the shape.
 */
public final class DutyShapes {

	public static final String KEY_PREFIX = "duty|";

	private DutyShapes() {
	}

	/**
	 * @param startPlatform the platform a train staying on the line overnight starts from, or 0 from the siding
	 * @return the key of the duty's path, or null if a route of it is gone
	 */
	public static String getKey(List<DepotDuties.Segment> segments, LongFunction<? extends PlanRoute> routes, int weekday, long startPlatform, boolean staysOnLine) {
		if (segments.isEmpty()) {
			return null;
		}
		final StringBuilder builder = new StringBuilder(KEY_PREFIX).append(startPlatform).append('|');
		for (final DepotDuties.Segment segment : segments) {
			final PlanRoute route = routes.apply(segment.routeId);
			if (route == null || route.getStops().size() < 2) {
				return null;
			}
			final boolean[] pass = getPassFlags(route, segment, weekday);
			builder.append(route.getRouteId()).append('@').append(getFirstStop(route, segment)).append('-').append(getLastStop(route, segment)).append(':');
			for (final boolean stopPass : pass) {
				builder.append(stopPass ? '1' : '0');
			}
			builder.append('>');
		}
		return builder.append(staysOnLine ? "stay" : "depot").toString();
	}

	public static int getFirstStop(PlanRoute route, DepotDuties.Segment segment) {
		return Math.max(0, Math.min(segment.fromStop, route.getStops().size() - 2));
	}

	public static int getLastStop(PlanRoute route, DepotDuties.Segment segment) {
		final int last = route.getStops().size() - 1;
		return segment.toStop < 0 ? last : Math.max(getFirstStop(route, segment) + 1, Math.min(segment.toStop, last));
	}

	/**
	 * Whether the service passes each stop it runs through, from where it joins to where it leaves. Its route's setting, then
	 * the service's own exception on the table it runs to that day. The stops where it joins and leaves are always stops.
	 */
	public static boolean[] getPassFlags(PlanRoute route, DepotDuties.Segment segment, int weekday) {
		final int first = getFirstStop(route, segment);
		final int last = getLastStop(route, segment);
		final RouteTimetable table = getTable(route, weekday);
		final java.util.Map<Integer, ServiceExceptions.StopException> exceptions = table == null ? null : table.exceptions.get(segment.service);
		final boolean[] pass = new boolean[last - first + 1];
		for (int i = first + 1; i < last; i++) {
			final ServiceExceptions.StopException exception = exceptions == null ? null : exceptions.get(i);
			final int exceptionPass = exception == null ? -1 : exception.pass;
			pass[i - first] = exceptionPass == 1 || exceptionPass != 0 && route.getStops().get(i).isPass();
		}
		return pass;
	}

	/**
	 * @return the table a route runs on a weekday, the first that has the day
	 */
	public static RouteTimetable getTable(PlanRoute route, int weekday) {
		for (final RouteTimetable table : route.getTimetable().getTables()) {
			if ((table.weekdays >> weekday & 1) != 0) {
				return table;
			}
		}
		return null;
	}

	/**
	 * @return the slot of the same siding a train staying overnight in this slot runs the next day
	 */
	public static int getNextSlot(DepotDuties.DayDuty day, int slotIndex) {
		return day.nextSlot < 0 ? slotIndex : day.nextSlot;
	}

	/**
	 * @return the slot whose train stays overnight to run this slot's duty on a weekday, or -1 if it starts from the siding
	 */
	public static int getOvernightSlot(List<DepotDuties.Slot> slots, int slotIndex, int weekday) {
		// Taken over by a train of another siding's duty, by number (P2-D)
		final DepotDuties.DayDuty own = slotIndex < slots.size() ? slots.get(slotIndex).getDuty(weekday) : null;
		if (own != null && own.fromOvernight) {
			return slotIndex;
		}
		final int dayBefore = Math.floorMod(weekday - 1, 7);
		for (int i = 0; i < slots.size(); i++) {
			final DepotDuties.DayDuty day = slots.get(i).getDuty(dayBefore);
			if (day != null && day.stayOnLine && !day.segments.isEmpty() && day.nextDutyId == 0 && getNextSlot(day, i) == slotIndex) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * @return the platform where the last segment of a duty ends, which is where a train staying overnight waits
	 */
	public static long getEndPlatform(DepotDuties.DayDuty day, LongFunction<? extends PlanRoute> routes) {
		if (day == null || day.segments.isEmpty()) {
			return 0;
		}
		final DepotDuties.Segment segment = day.segments.get(day.segments.size() - 1);
		final PlanRoute route = routes.apply(segment.routeId);
		return route == null || route.getStops().size() < 2 ? 0 : route.getStops().get(getLastStop(route, segment)).getPlatformId();
	}

	/**
	 * @return the platform where a duty's first segment starts
	 */
	public static long getStartPlatform(DepotDuties.DayDuty day, LongFunction<? extends PlanRoute> routes) {
		if (day == null || day.segments.isEmpty()) {
			return 0;
		}
		final DepotDuties.Segment segment = day.segments.get(0);
		final PlanRoute route = routes.apply(segment.routeId);
		return route == null || route.getStops().size() < 2 ? 0 : route.getStops().get(getFirstStop(route, segment)).getPlatformId();
	}

	/**
	 * @return the key of the path a slot runs on a weekday, starting from the siding or from where a train stayed overnight
	 */
	public static String getSlotKey(List<DepotDuties.Slot> slots, int slotIndex, int weekday, LongFunction<? extends PlanRoute> routes) {
		final DepotDuties.DayDuty day = slotIndex < slots.size() ? slots.get(slotIndex).getDuty(weekday) : null;
		if (day == null || day.segments.isEmpty()) {
			return null;
		}
		final int overnightSlot = getOvernightSlot(slots, slotIndex, weekday);
		final long startPlatform = overnightSlot < 0 ? 0 : getEndPlatform(slots.get(overnightSlot).getDuty(Math.floorMod(weekday - 1, 7)), routes);
		final String key = getKey(day.segments, routes, weekday, startPlatform, day.stayOnLine);
		if (key == null) {
			return null;
		}
		// The named deadhead paths out and back, as the duty has them (运用补完 step 3): a path of its own for each
		final StringBuilder builder = new StringBuilder(key);
		if (day.outPathId != 0 && overnightSlot < 0) {
			builder.append(PATH_MARK).append('o').append(day.outPathId);
		}
		if (day.backPathId != 0 && !day.stayOnLine) {
			builder.append(PATH_MARK).append('b').append(day.backPathId);
		}
		return builder.toString();
	}

	/**
	 * @return the named deadhead path out ({@code out}) or back a duty's key says, or 0 for the way by the rules
	 */
	public static long getPathId(String key, boolean out) {
		if (key == null) {
			return 0;
		}
		final String mark = PATH_MARK + (out ? "o" : "b");
		final int index = key.indexOf(mark);
		if (index < 0) {
			return 0;
		}
		int end = index + mark.length();
		while (end < key.length() && (Character.isDigit(key.charAt(end)) || key.charAt(end) == '-' && end == index + mark.length())) {
			end++;
		}
		try {
			return Long.parseLong(key.substring(index + mark.length(), end));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static final String PATH_MARK = "!";

	/**
	 * @return when the operating day of a time started
	 */
	public static long getDayStart(long millis) {
		return millis - OperatingDay.getTimeInDay(millis);
	}

	/**
	 * @return a service of a duty as a full timestamp on the operating day starting at {@code dayStart}
	 */
	public static long getServiceMillis(long dayStart, int service) {
		return dayStart + OperatingDay.getTimeInDay(service);
	}

	public static long getDayNumber(long dayStart) {
		return Math.floorDiv(dayStart + OperatingDay.MILLIS_PER_DAY / 2, (long) OperatingDay.MILLIS_PER_DAY);
	}
}
