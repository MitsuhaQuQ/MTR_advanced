package transitsim.sim;

import org.junit.jupiter.api.Test;
import transitsim.motion.MotionEvent;
import transitsim.plan.Buffers;
import transitsim.plan.DutyPlan;
import transitsim.plan.StopTimes;
import transitsim.plan.TripPath;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How much longer than planned trains take out, round and back, measured from their events (D1).
 */
class BufferRecorderTest {

	private static final long SECOND = 1000;
	private static final int TICKS_PER_SECOND = 20;
	private static final long SIDING_ID = 100;
	private long sequence;

	private static List<TestTrack> makePath(long... platformIds) {
		final List<TestTrack> path = new ArrayList<>();
		for (int i = 0; i < platformIds.length; i++) {
			path.add(new TestTrack(i * 20, i * 20 + 20, platformIds[i] == 0, platformIds[i], i == 0 ? 0 : 10, i));
		}
		return path;
	}

	private static long[] stop(int rail, long platformId, int seconds) {
		return new long[]{rail, platformId, (long) seconds * TICKS_PER_SECOND, (long) (seconds + 10) * TICKS_PER_SECOND};
	}

	/**
	 * Out to platform 1, route 1 to platform 3, round a turn back rail (rail 4) to platform 4, route 2 to platform 5, back.
	 */
	private static TripPath<TestTrack> duty() {
		final List<TestTrack> path = makePath(0, 1, 2, 3, 0, 4, 5, 0);
		final DutyPlan<TestTrack, Object> dutyPlan = new DutyPlan<>("duty|test", Arrays.asList(1L, 2L), new HashMap<>(), new HashMap<>(), new HashMap<>(), path, new int[]{1, 5}, null, null, false, false);
		// Round from platform 3 to 4: leaves at 250 s, stands 10 s on the turn back rail, at platform 4 at 480 s, so 220 s running
		return TripPath.ofDuty(dutyPlan, new int[]{1, 5}, path, StopTimes.of(stop(1, 1, 0), stop(2, 2, 120), stop(3, 3, 240), stop(4, 0, 360), stop(5, 4, 480), stop(6, 5, 600)));
	}

	private void event(BufferRecorder recorder, TripPath<TestTrack> tripPath, long seconds, MotionEvent.Type type, int rail) {
		recorder.onEvent(new MotionEvent(seconds * SECOND, type, 7, ++sequence, rail, 0), SIDING_ID, tripPath);
	}

	@Test
	void aTurnLongerThanPlannedLessTheTimeStoodOnTheTurnBackRailIsMeasured() {
		final BufferRecorder recorder = new BufferRecorder();
		final TripPath<TestTrack> tripPath = duty();
		for (int i = 0; i < 3; i++) {
			// 400 s from leaving platform 3 to reaching 4, of which 100 s standing on the turn back rail: 300 s running, 80 s more
			event(recorder, tripPath, 1000 * i, MotionEvent.Type.DEPARTED, 3);
			event(recorder, tripPath, 1000 * i + 60, MotionEvent.Type.STOPPED, 4);
			event(recorder, tripPath, 1000 * i + 160, MotionEvent.Type.DEPARTED, 4);
			event(recorder, tripPath, 1000 * i + 400, MotionEvent.Type.STOPPED, 5);
		}
		assertEquals(80 * SECOND, recorder.getBuffers().turn(3, 4));
		assertTrue(recorder.takeChanged());
		assertFalse(recorder.takeChanged());
	}

	@Test
	void aLegNoLongerThanPlannedKeepsNoBuffer() {
		final BufferRecorder recorder = new BufferRecorder();
		final TripPath<TestTrack> tripPath = duty();
		// 230 s running, 10 s more than planned: within the tolerance
		event(recorder, tripPath, 0, MotionEvent.Type.DEPARTED, 3);
		event(recorder, tripPath, 230, MotionEvent.Type.STOPPED, 5);
		assertTrue(recorder.getBuffers().isEmpty());
		assertFalse(recorder.takeChanged());
	}

	@Test
	void theWayOutIsMeasuredFromTheSiding() {
		final BufferRecorder recorder = new BufferRecorder();
		final TripPath<TestTrack> tripPath = duty();
		// Planned as reaching the first stop as soon as it leaves; stopping short of a taken rail on the way counts
		event(recorder, tripPath, 0, MotionEvent.Type.DEPARTED, 0);
		event(recorder, tripPath, 30, MotionEvent.Type.STOPPED, 0);
		event(recorder, tripPath, 90, MotionEvent.Type.STOPPED, 1);
		assertEquals(90 * SECOND, recorder.getBuffers().out(SIDING_ID, 1));
	}

	@Test
	void stoppingAtAnotherPlatformEndsTheLegUnmeasured() {
		final BufferRecorder recorder = new BufferRecorder();
		final TripPath<TestTrack> tripPath = duty();
		event(recorder, tripPath, 0, MotionEvent.Type.DEPARTED, 0);
		event(recorder, tripPath, 90, MotionEvent.Type.STOPPED, 2);
		event(recorder, tripPath, 100, MotionEvent.Type.STOPPED, 1);
		assertTrue(recorder.getBuffers().isEmpty());
	}

	@Test
	void aLegHeldUpForLongIsLeftOut() {
		final BufferRecorder recorder = new BufferRecorder();
		final TripPath<TestTrack> tripPath = duty();
		event(recorder, tripPath, 0, MotionEvent.Type.DEPARTED, 3);
		event(recorder, tripPath, 220 + 21 * 60, MotionEvent.Type.STOPPED, 5);
		assertTrue(recorder.getBuffers().isEmpty());
	}

	@Test
	void theBufferIsThePercentileOfTheLatestMeasurements() {
		final BufferRecorder recorder = new BufferRecorder();
		final String key = Buffers.key(Buffers.TURN, 3, 4);
		// 30 measurements of 1 to 30 s: kept once the 85th percentile reaches 15 s, and not changed while within 15 s of it
		// (the latest 20, 11 to 30 s, have 27 s), so small changes don't plan the duties again
		for (int i = 1; i <= 30; i++) {
			recorder.add(key, i * SECOND);
		}
		assertEquals(15 * SECOND, recorder.getBuffers().turn(3, 4));
		for (int i = 0; i < 20; i++) {
			recorder.add(key, 40 * SECOND);
		}
		assertEquals(40 * SECOND, recorder.getBuffers().turn(3, 4));
		assertEquals(0, BufferRecorder.getBuffer(Arrays.asList(SECOND, 2 * SECOND, 14 * SECOND)));
		assertEquals(15 * SECOND, BufferRecorder.getBuffer(Arrays.asList(-SECOND, 15 * SECOND)));

		// Saved and read again, the same
		final BufferRecorder read = new BufferRecorder();
		read.readList(recorder.toList());
		assertEquals(recorder.getBuffers(), read.getBuffers());
		assertEquals(recorder.toList(), read.toList());
		assertEquals(recorder.getBuffers(), Buffers.fromList(recorder.getBuffers().toList()));

		recorder.clear();
		assertTrue(recorder.getBuffers().isEmpty());
		assertTrue(recorder.takeChanged());
	}
}
