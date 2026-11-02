package mtr.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimetableConfigTest {

	private static final long HOUR = 60 * 60 * 1000;
	private static final long DAY = Depot.MILLISECONDS_PER_DAY;
	/**
	 * Monday the 5th of January 2026 at midnight in UTC.
	 */
	private static final long MONDAY = 20458 * DAY;

	@TempDir
	Path folder;

	@AfterEach
	void restore() {
		TimetableConfig.load(null);
		TimetableConfig.set("UTC", 0);
	}

	@Test
	void weekdaysStartFromMonday() {
		TimetableConfig.set("UTC", 0);

		assertEquals(0, TimetableConfig.getWeekday(MONDAY));
		assertEquals(6, TimetableConfig.getWeekday(MONDAY - 1));
		assertEquals(6, TimetableConfig.getWeekday(MONDAY + 6 * DAY));
		assertEquals(0, TimetableConfig.getWeekday(MONDAY + 7 * DAY));
	}

	@Test
	void timeZoneDecidesTheDay() {
		TimetableConfig.set("+08:00", 0);

		assertEquals(0, TimetableConfig.getWeekday(MONDAY - 8 * HOUR));
		assertEquals(6, TimetableConfig.getWeekday(MONDAY - 8 * HOUR - 1));
	}

	@Test
	void trainsAfterMidnightBelongToTheDayBefore() {
		TimetableConfig.set("UTC", 3);

		assertEquals(6, TimetableConfig.getWeekday(MONDAY + 2 * HOUR));
		assertEquals(0, TimetableConfig.getWeekday(MONDAY + 3 * HOUR));
	}

	@Test
	void timeZonesWithDaylightSavingFollowTheDate() {
		TimetableConfig.set("Europe/London", 0);

		// The 29th of June 2026 is a Monday, an hour ahead of UTC
		final long summerMonday = MONDAY + 25 * 7 * DAY;
		assertEquals(0, TimetableConfig.getWeekday(summerMonday - HOUR));
		assertEquals(6, TimetableConfig.getWeekday(summerMonday - HOUR - 1));
	}

	@Test
	void servicesSortFromTheStartOfTheOperatingDay() {
		TimetableConfig.set("Asia/Tokyo", 3);

		// 03:00 in Tokyo is 18:00 in UTC, and midnight in UTC is 09:00 there
		assertEquals(18 * HOUR, TimetableConfig.getDayStartMillis());
		assertEquals(0, TimetableConfig.getTimeInDay(18 * HOUR));
		assertEquals(6 * HOUR, TimetableConfig.getTimeInDay(0));
		// A last train at 02:00 the next morning comes at the end of the day
		assertEquals(23 * HOUR, TimetableConfig.getTimeInDay(17 * HOUR));
	}

	@Test
	void missingFileIsCreatedWithThisComputersTimeZone() throws IOException {
		final Path path = folder.resolve("sub").resolve(TimetableConfig.FILE_NAME);
		TimetableConfig.load(path);

		assertTrue(Files.exists(path));
		assertTrue(new String(Files.readAllBytes(path), StandardCharsets.UTF_8).contains(TimetableConfig.getTimeZoneId()));
		assertEquals(3, TimetableConfig.getDayStartHour());
	}

	@Test
	void settingsComeFromTheFile() throws IOException {
		final Path path = folder.resolve(TimetableConfig.FILE_NAME);
		Files.write(path, "{\"timeZone\": \"Asia/Tokyo\", \"dayStartHour\": 4}".getBytes(StandardCharsets.UTF_8));
		TimetableConfig.load(path);

		assertEquals("Asia/Tokyo", TimetableConfig.getTimeZoneId());
		assertEquals(4, TimetableConfig.getDayStartHour());
	}

	@Test
	void brokenFileKeepsTheSettingsInUse() throws IOException {
		TimetableConfig.set("Asia/Tokyo", 4);
		final Path path = folder.resolve(TimetableConfig.FILE_NAME);
		Files.write(path, "{\"timeZone\": \"Not/AZone\"}".getBytes(StandardCharsets.UTF_8));
		TimetableConfig.load(path);

		assertEquals("Asia/Tokyo", TimetableConfig.getTimeZoneId());
		assertEquals(4, TimetableConfig.getDayStartHour());
	}
}
