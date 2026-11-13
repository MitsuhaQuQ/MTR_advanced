package transitsim.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ways out corrected by how late their trains reach the first stop, and left alone where leaving earlier doesn't help
 * (2026-11-13).
 */
class OutLeadsTest {

	private static final long SECOND = 1000;
	private static final String KEY = "0:100:1";

	private static long runs(OutLeads outLeads, long adopted, long... lateness) {
		long result = -1;
		for (final long late : lateness) {
			result = outLeads.add(KEY, late * SECOND, adopted);
		}
		return result;
	}

	@Test
	void aWayOutRunningLateLeavesThatMuchEarlier() {
		final OutLeads outLeads = new OutLeads();
		// The middle of three runs counts: 60 s late, so 60 s earlier
		assertEquals(-1, runs(outLeads, 0, 50, 60));
		assertEquals(60 * SECOND, outLeads.add(KEY, 90 * SECOND, 0));
		// Leaving earlier helped: on time now, nothing changes and nothing is marked
		assertEquals(-1, runs(outLeads, 60 * SECOND, 0, 5, -3));
		assertTrue(outLeads.getIneffective().isEmpty());
	}

	@Test
	void lateByLessThanTheToleranceChangesNothing() {
		assertEquals(-1, runs(new OutLeads(), 0, 10, 12, 14));
	}

	@Test
	void aWayOutStillAsLateAfterLeavingEarlierIsTakenBackAndMarked() {
		final OutLeads outLeads = new OutLeads();
		assertEquals(375 * SECOND, runs(outLeads, 0, 375, 375, 380));
		// Still 370 s late after leaving 375 s earlier: waiting for something on the clock, so taken back
		assertEquals(0, runs(outLeads, 375 * SECOND, 370, 372, 369));
		assertEquals(370 * SECOND, (long) outLeads.getIneffective().get(KEY));
		// And left alone after that
		assertEquals(-1, runs(outLeads, 0, 400, 400, 400));
		// Until what was measured is cleared
		outLeads.clear();
		assertEquals(400 * SECOND, runs(outLeads, 0, 400, 400, 400));
	}

	@Test
	void aWayOutHelpedPartlyKeepsGoing() {
		final OutLeads outLeads = new OutLeads();
		assertEquals(100 * SECOND, runs(outLeads, 0, 100, 100, 100));
		// 40 s late after leaving 100 s earlier: better by more than half, so 40 s more
		assertEquals(140 * SECOND, runs(outLeads, 100 * SECOND, 40, 40, 40));
		assertTrue(outLeads.getIneffective().isEmpty());
	}
}
