package transitsim.planning;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * Timetables repeat every week. A week offset is the time since Monday 00:00 on the server's wall clock,
 * and a value past {@link #WEEK_MILLIS} belongs to the following week.
 */
public final class OperationTime {

	public static final long SECOND_MILLIS = 1000;
	public static final long MINUTE_MILLIS = 60 * SECOND_MILLIS;
	public static final long HOUR_MILLIS = 60 * MINUTE_MILLIS;
	public static final long DAY_MILLIS = 24 * HOUR_MILLIS;
	public static final long WEEK_MILLIS = 7 * DAY_MILLIS;
	public static final int ALL_DAYS = 0b1111111;
	public static final int WEEKDAYS = 0b0011111;
	public static final int WEEKENDS = 0b1100000;

	private OperationTime() {
	}

	public static long weekOffset(long epochMillis, ZoneId zone) {
		final LocalDateTime local = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone);
		return ChronoUnit.MILLIS.between(mondayOf(local.toLocalDate()).atStartOfDay(), local);
	}

	/**
	 * Converts a week offset into a full planned timestamp, using the week containing {@code weekAnchorEpochMillis}.
	 */
	public static long plannedTimestamp(long weekAnchorEpochMillis, ZoneId zone, long weekOffset) {
		final LocalDate monday = mondayOf(LocalDateTime.ofInstant(Instant.ofEpochMilli(weekAnchorEpochMillis), zone).toLocalDate());
		return monday.atStartOfDay().plus(weekOffset, ChronoUnit.MILLIS).atZone(zone).toInstant().toEpochMilli();
	}

	public static long normalize(long weekOffset) {
		return Math.floorMod(weekOffset, WEEK_MILLIS);
	}

	private static LocalDate mondayOf(LocalDate date) {
		return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
	}
}
