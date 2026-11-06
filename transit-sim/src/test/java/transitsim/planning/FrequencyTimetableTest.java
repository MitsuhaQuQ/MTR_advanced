package transitsim.planning;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A depot's frequencies or list of departures as its routes' services (R2), as the game sent its trains out.
 */
class FrequencyTimetableTest {

	@Test
	void minutesBetweenTrainsFollowTheRealClock() {
		final int[] headways = new int[24];
		headways[7] = 10;
		headways[8] = 25;
		final List<Integer> departures = FrequencyTimetable.departuresOfHeadways(headways, 4096);
		final int hour = 60 * 60 * 1000, minute = 60 * 1000;
		assertEquals(Arrays.asList(7 * hour, 7 * hour + 10 * minute, 7 * hour + 20 * minute, 7 * hour + 30 * minute, 7 * hour + 40 * minute, 7 * hour + 50 * minute, 8 * hour, 8 * hour + 25 * minute, 8 * hour + 50 * minute), departures);
		assertTrue(FrequencyTimetable.departuresOfHeadways(new int[24], 4096).isEmpty());
	}

	@Test
	void anOldFrequencyKeepsItsSpacingToTheMinute() {
		assertEquals(0, FrequencyTimetable.headwayOf(0));
		// 200 seconds
		assertEquals(3, FrequencyTimetable.headwayOf(1));
		// 25 seconds, at least a minute
		assertEquals(1, FrequencyTimetable.headwayOf(8));
	}

	@Test
	void aFrequencySpacesTrainsAsTheGameDid() {
		final int[] frequencies = new int[24];
		frequencies[0] = 8;
		final List<Integer> departures = FrequencyTimetable.departuresOf(frequencies, 4096);
		// Every 25 seconds in the game's first hour, the first 50 seconds of each 20 minutes
		assertEquals(Arrays.asList(0, 25000), departures.subList(0, 2));
		assertEquals(Integer.valueOf(20 * 60 * 1000), departures.get(2), "the game's next day");
		assertEquals(72 * 2, departures.size(), "the game's day comes round 72 times a real day");
	}

	@Test
	void hoursWithoutTrainsAreSkipped() {
		final int[] frequencies = new int[24];
		frequencies[2] = 1;
		final List<Integer> departures = FrequencyTimetable.departuresOf(frequencies, 4096);
		// One train at the start of the game's third hour, spaced 200 seconds, which outlasts the hour
		assertEquals(Integer.valueOf(2 * 50000), departures.get(0));
		assertEquals(Integer.valueOf(20 * 60 * 1000 + 2 * 50000), departures.get(1));
		assertTrue(FrequencyTimetable.departuresOf(new int[24], 4096).isEmpty());
	}

	@Test
	void manyTrainsStopAtTheLimit() {
		final int[] frequencies = new int[24];
		Arrays.fill(frequencies, 16);
		assertEquals(4096, FrequencyTimetable.departuresOf(frequencies, 4096).size());
	}

	@Test
	void departuresAreCleaned() {
		assertEquals(Arrays.asList(5, 1000), FrequencyTimetable.cleaned(Arrays.asList(1000, 5, FrequencyTimetable.DAY_MILLIS + 5), 10));
	}

	@Test
	void theNextRoutesFollowTheOneBefore() {
		final long[][] up = {{0, 0}, {120000, 140000}, {300000, 300000}};
		final List<List<Integer>> services = FrequencyTimetable.chain(Arrays.asList(0, 600000), Arrays.asList(up, up, null));
		assertEquals(3, services.size());
		assertEquals(Arrays.asList(360000, 960000), services.get(1));
		assertEquals(Arrays.asList(720000, 1320000), services.get(2));
		// With the times of a route not known, the routes after it get no services
		assertEquals(1, FrequencyTimetable.chain(Collections.singletonList(0), Arrays.asList(null, up)).size());
	}
}
