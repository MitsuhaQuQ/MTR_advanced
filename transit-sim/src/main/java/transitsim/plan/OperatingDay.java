package transitsim.plan;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Times are saved in UTC. The time zone only decides how they are shown and which day of the week a service belongs to,
 * so players in different places see the same timetable. The host reads the zone and the hour an operating day starts
 * from its settings and sets them here.
 */
public final class OperatingDay {

	public static final int MILLIS_PER_DAY = 24 * 60 * 60 * 1000;
	public static final int DEFAULT_DAY_START_HOUR = 3;

	private static final int HOURS_PER_DAY = 24;
	private static final long MILLIS_PER_HOUR = 60 * 60 * 1000;
	private static final int DAYS_PER_WEEK = 7;
	/**
	 * Day 0 in Unix time was a Thursday, which is the fourth day counting from Monday.
	 */
	private static final int FIRST_DAY_WEEKDAY = 3;

	private static volatile ZoneId timeZone = ZoneOffset.UTC;
	private static volatile int dayStartHour = DEFAULT_DAY_START_HOUR;

	private OperatingDay() {
	}

	/**
	 * @param newTimeZone     a time zone like {@code Asia/Shanghai} or {@code +08:00}
	 * @param newDayStartHour the hour of the day when one operating day ends and the next one starts
	 */
	public static void set(String newTimeZone, int newDayStartHour) {
		timeZone = ZoneId.of(newTimeZone);
		dayStartHour = Math.floorMod(newDayStartHour, HOURS_PER_DAY);
	}

	public static ZoneId getTimeZone() {
		return timeZone;
	}

	public static int getDayStartHour() {
		return dayStartHour;
	}

	/**
	 * @param millis a time of day saved in UTC
	 * @return how long after the start of its operating day this time is, so services sort in the order trains run them
	 */
	public static long getTimeInDay(long millis) {
		return Math.floorMod(millis + getOffsetMillis() - dayStartHour * MILLIS_PER_HOUR, (long) MILLIS_PER_DAY);
	}

	/**
	 * @return the time of day in UTC when an operating day starts
	 */
	public static int getDayStartMillis() {
		return (int) Math.floorMod(dayStartHour * MILLIS_PER_HOUR - getOffsetMillis(), (long) MILLIS_PER_DAY);
	}

	/**
	 * A train running after midnight still belongs to the day it started in, like the last trains of a real timetable.
	 *
	 * @return the day of the week in the operating day of this time, from 0 for Monday
	 */
	public static int getWeekday(long millis) {
		final long localMillis = millis + timeZone.getRules().getOffset(Instant.ofEpochMilli(millis)).getTotalSeconds() * 1000L;
		final long day = Math.floorDiv(localMillis - dayStartHour * MILLIS_PER_HOUR, (long) MILLIS_PER_DAY);
		return (int) Math.floorMod(day + FIRST_DAY_WEEKDAY, (long) DAYS_PER_WEEK);
	}

	private static long getOffsetMillis() {
		return timeZone.getRules().getOffset(Instant.now()).getTotalSeconds() * 1000L;
	}
}
