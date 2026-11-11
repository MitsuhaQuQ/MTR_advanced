package mtr.data;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Other mods reading the arrivals at a platform are given only trains that stop there and haven't come already (0.65.31):
 * they can't tell a passing train or a past plan, which this mod's own displays leave out.
 */
class ScheduleForOtherModsTest {

	@Test
	void passingTrainsAndPastPlansAreLeftOut() {
		final long now = 1_000_000;
		final ScheduleEntry stopping = new ScheduleEntry(now + 60_000, 8, 1, 2, 10, false);
		final ScheduleEntry passing = new ScheduleEntry(now + 30_000, 8, 2, 2, 11, true);
		final ScheduleEntry futurePlan = new ScheduleEntry(now + 90_000, 8, 1, 2, 0, false, now + 90_000, true, false);
		final ScheduleEntry pastPlan = new ScheduleEntry(now - 5_000, 8, 1, 2, 0, false, now - 5_000, true, false);
		final ScheduleEntry lateTrain = new ScheduleEntry(now - 5_000, 8, 1, 2, 12, false);
		final List<ScheduleEntry> shown = ScheduleEntry.forOtherMods(Arrays.asList(stopping, passing, futurePlan, pastPlan, lateTrain), now);
		assertEquals(Arrays.asList(stopping, futurePlan, lateTrain), shown);
		assertNull(ScheduleEntry.forOtherMods(null, now));
	}
}
