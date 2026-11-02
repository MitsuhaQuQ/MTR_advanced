package mtr.data;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;

/**
 * Times are saved in UTC. The time zone only decides how they are shown and which day of the week a service belongs to,
 * so players in different places see the same timetable. It comes from a file in the config folder, and the server sends it
 * to players.
 */
public final class TimetableConfig {

	private static final int DEFAULT_DAY_START_HOUR = 3;

	private static volatile Path configPath;
	private static volatile ZoneId timeZone = ZoneOffset.UTC;
	private static volatile int dayStartHour = DEFAULT_DAY_START_HOUR;

	public static final String FILE_NAME = "mtr_timetable.json";

	private static final int HOURS_PER_DAY = 24;
	private static final long MILLIS_PER_HOUR = 60 * 60 * 1000;
	private static final int DAYS_PER_WEEK = 7;
	/**
	 * Day 0 in Unix time was a Thursday, which is the fourth day counting from Monday.
	 */
	private static final int FIRST_DAY_WEEKDAY = 3;
	private static final String KEY_TIME_ZONE = "timeZone";
	private static final String KEY_DAY_START_HOUR = "dayStartHour";

	private TimetableConfig() {
	}

	/**
	 * Reads the file, or creates it with the time zone of this computer. If it can't be read, the settings stay as they were.
	 */
	public static void load(Path path) {
		configPath = path;
		reload();
	}

	public static void reload() {
		final Path path = configPath;
		if (path != null) {
			try {
				if (Files.exists(path)) {
					final JsonObject jsonObject = new JsonParser().parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
					set(jsonObject.get(KEY_TIME_ZONE).getAsString(), jsonObject.has(KEY_DAY_START_HOUR) ? jsonObject.get(KEY_DAY_START_HOUR).getAsInt() : DEFAULT_DAY_START_HOUR);
				} else {
					final String newTimeZone = ZoneId.systemDefault().getId();
					set(newTimeZone, DEFAULT_DAY_START_HOUR);
					final JsonObject jsonObject = new JsonObject();
					jsonObject.addProperty(KEY_TIME_ZONE, newTimeZone);
					jsonObject.addProperty(KEY_DAY_START_HOUR, DEFAULT_DAY_START_HOUR);
					Files.createDirectories(path.getParent());
					Files.write(path, new GsonBuilder().setPrettyPrinting().create().toJson(jsonObject).getBytes(StandardCharsets.UTF_8));
				}
			} catch (Exception e) {
				System.out.println("Failed to read Minecraft Transit Railway timetable settings from " + path);
				e.printStackTrace();
			}
		}
	}

	/**
	 * @param newTimeZone   a time zone like {@code Asia/Shanghai} or {@code +08:00}
	 * @param newDayStartHour the hour of the day when one operating day ends and the next one starts
	 */
	public static void set(String newTimeZone, int newDayStartHour) {
		timeZone = ZoneId.of(newTimeZone);
		dayStartHour = Math.floorMod(newDayStartHour, HOURS_PER_DAY);
	}

	public static String getTimeZoneId() {
		return timeZone.getId();
	}

	public static TimeZone getTimeZone() {
		return TimeZone.getTimeZone(timeZone);
	}

	public static int getDayStartHour() {
		return dayStartHour;
	}

	/**
	 * @param millis a time of day saved in UTC
	 * @return how long after the start of its operating day this time is, so services sort in the order trains run them
	 */
	public static long getTimeInDay(long millis) {
		return Math.floorMod(millis + getOffsetMillis() - dayStartHour * MILLIS_PER_HOUR, (long) Depot.MILLISECONDS_PER_DAY);
	}

	/**
	 * @return the time of day in UTC when an operating day starts
	 */
	public static int getDayStartMillis() {
		return (int) Math.floorMod(dayStartHour * MILLIS_PER_HOUR - getOffsetMillis(), (long) Depot.MILLISECONDS_PER_DAY);
	}

	private static long getOffsetMillis() {
		return timeZone.getRules().getOffset(Instant.now()).getTotalSeconds() * 1000L;
	}

	/**
	 * A train running after midnight still belongs to the day it started in, like the last trains of a real timetable.
	 *
	 * @return the day of the week in the operating day of this time, from 0 for Monday
	 */
	public static int getWeekday(long millis) {
		final long localMillis = millis + timeZone.getRules().getOffset(Instant.ofEpochMilli(millis)).getTotalSeconds() * 1000L;
		final long day = Math.floorDiv(localMillis - dayStartHour * MILLIS_PER_HOUR, (long) Depot.MILLISECONDS_PER_DAY);
		return (int) Math.floorMod(day + FIRST_DAY_WEEKDAY, (long) DAYS_PER_WEEK);
	}
}
