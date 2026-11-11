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

	/**
	 * A train held at the platform longer than its stop time is told as arriving now until its stop time is all that is
	 * left, then as arriving that long before it leaves, so a stop time counted down from the arrival ends as it leaves
	 * and doesn't jump back between updates (0.67.0); the departure goes with it through the packet.
	 */
	@Test
	void aStandingTrainArrivesAsLongBeforeItLeavesAsTheStopTime() {
		final long departure = 1_000_000;
		final ScheduleEntry entry = new ScheduleEntry(0, 8, 1, 2, 10, false, 0, false, false, departure);
		assertEquals(departure - 100_000, entry.atTime(departure - 100_000, 20_000).arrivalMillis);
		assertEquals(departure - 20_000, entry.atTime(departure - 15_000, 20_000).arrivalMillis);
		assertEquals(departure - 20_000, entry.atTime(departure - 1_000, 20_000).arrivalMillis);
		assertTrue(entry.atTime(departure - 100_000, 20_000).waitingToCountDown);
		assertFalse(entry.atTime(departure - 15_000, 20_000).waitingToCountDown);
		final ScheduleEntry moving = new ScheduleEntry(500, 8, 1, 2, 10, false);
		assertSame(moving, moving.atTime(departure, 20_000));
		// Held for something whose end isn't known: the planned departure is only shown, the train arrived as told
		final ScheduleEntry held = new ScheduleEntry(departure - 50_000, 8, 1, 2, 10, false, 0, false, false, -departure);
		assertSame(held, held.atTime(departure - 10_000, 20_000));
		assertTrue(held.waitingToCountDown);

		final net.minecraft.network.FriendlyByteBuf packet = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
		entry.writePacket(packet);
		assertEquals(departure, new ScheduleEntry(packet).departureMillis);
	}
}
