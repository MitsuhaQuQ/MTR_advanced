package mtr.data;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import transitsim.plan.OperatingDay;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.TimeZone;

/**
 * Times are saved in UTC. The time zone only decides how they are shown and which day of the week a service belongs to,
 * so players in different places see the same timetable. It comes from a file in the config folder, and the server sends it
 * to players. The arithmetic is the component's {@link OperatingDay}; this class reads and writes the file.
 */
public final class TimetableConfig {

	private static volatile Path configPath;

	public static final String FILE_NAME = "mtr_timetable.json";

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
					set(jsonObject.get(KEY_TIME_ZONE).getAsString(), jsonObject.has(KEY_DAY_START_HOUR) ? jsonObject.get(KEY_DAY_START_HOUR).getAsInt() : OperatingDay.DEFAULT_DAY_START_HOUR);
				} else {
					final String newTimeZone = ZoneId.systemDefault().getId();
					set(newTimeZone, OperatingDay.DEFAULT_DAY_START_HOUR);
					final JsonObject jsonObject = new JsonObject();
					jsonObject.addProperty(KEY_TIME_ZONE, newTimeZone);
					jsonObject.addProperty(KEY_DAY_START_HOUR, OperatingDay.DEFAULT_DAY_START_HOUR);
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
	 * @param newTimeZone     a time zone like {@code Asia/Shanghai} or {@code +08:00}
	 * @param newDayStartHour the hour of the day when one operating day ends and the next one starts
	 */
	public static void set(String newTimeZone, int newDayStartHour) {
		OperatingDay.set(newTimeZone, newDayStartHour);
	}

	public static String getTimeZoneId() {
		return OperatingDay.getTimeZone().getId();
	}

	public static TimeZone getTimeZone() {
		return TimeZone.getTimeZone(OperatingDay.getTimeZone());
	}

	public static int getDayStartHour() {
		return OperatingDay.getDayStartHour();
	}

	/**
	 * See {@link OperatingDay#getTimeInDay(long)}.
	 */
	public static long getTimeInDay(long millis) {
		return OperatingDay.getTimeInDay(millis);
	}

	/**
	 * See {@link OperatingDay#getDayStartMillis()}.
	 */
	public static int getDayStartMillis() {
		return OperatingDay.getDayStartMillis();
	}

	/**
	 * See {@link OperatingDay#getWeekday(long)}.
	 */
	public static int getWeekday(long millis) {
		return OperatingDay.getWeekday(millis);
	}
}
