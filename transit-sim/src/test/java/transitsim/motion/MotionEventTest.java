package transitsim.motion;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the motion rules tell the host (S2d): events while a train runs, and the driver's commands.
 */
class MotionEventTest {

	private static final int TICKS = 4000;

	@Test
	void tellsWhatHappenedOnTheWay() {
		final MotionFixture fixture = line();
		run(fixture, TICKS);
		final List<MotionEvent.Type> types = fixture.events.stream().map(event -> event.type).filter(type -> type != MotionEvent.Type.SEGMENT_REPLACED).collect(Collectors.toList());
		assertEquals(Arrays.asList(
				MotionEvent.Type.DEPARTED, MotionEvent.Type.STOPPED,
				MotionEvent.Type.DEPARTED, MotionEvent.Type.STOPPED,
				MotionEvent.Type.DEPARTED, MotionEvent.Type.RETURNED
		), types.subList(0, 6));
		// Stopped at the two stations, at the end of their rails
		assertEquals(2, fixture.events.stream().filter(event -> event.type == MotionEvent.Type.STOPPED).findFirst().orElseThrow(AssertionError::new).rail);
		assertEquals(4, fixture.events.stream().filter(event -> event.type == MotionEvent.Type.STOPPED).skip(1).findFirst().orElseThrow(AssertionError::new).rail);
	}

	@Test
	void sameRunSameEvents() {
		final MotionFixture first = line();
		final MotionFixture second = line();
		first.blockedAt = second.blockedAt = (tick, rail) -> rail == 4 && tick < 1500;
		run(first, TICKS);
		run(second, TICKS);
		assertEquals(describe(first.events), describe(second.events));
		assertTrue(first.events.stream().anyMatch(event -> event.type == MotionEvent.Type.SEGMENT_REPLACED));
	}

	@Test
	void theOldRulesAndClientsHaveNone() {
		final MotionFixture legacy = line();
		for (int i = 0; i < TICKS; i++) {
			legacy.step(true, 1);
		}
		assertTrue(legacy.events.isEmpty());
		final MotionFixture client = line();
		client.clientSide = true;
		run(client, TICKS);
		assertTrue(client.events.isEmpty());
	}

	@Test
	void sortsByTimeThenKindThenTrainThenOrder() {
		final List<MotionEvent> events = new ArrayList<>();
		events.add(new MotionEvent(100, MotionEvent.Type.DEPARTED, 1, 5, 0, 0));
		events.add(new MotionEvent(100, MotionEvent.Type.STOPPED, 2, 1, 0, 0));
		events.add(new MotionEvent(100, MotionEvent.Type.STOPPED, 1, 9, 0, 0));
		events.add(new MotionEvent(50, MotionEvent.Type.DEPARTED, 3, 1, 0, 0));
		events.add(new MotionEvent(100, MotionEvent.Type.STOPPED, 1, 8, 0, 0));
		events.sort(null);
		assertEquals("50/DEPARTED/3/1 100/STOPPED/1/8 100/STOPPED/1/9 100/STOPPED/2/1 100/DEPARTED/1/5", events.stream().map(event -> event.millis + "/" + event.type + "/" + event.trainId + "/" + event.sequence).collect(Collectors.joining(" ")));
	}

	@Test
	void driverCommandsMoveTheNotchAStepAtATime() {
		final MotionFixture fixture = new MotionFixture();
		fixture.manualNotch = 0;
		assertTrue(TrainMotion.applyCommand(fixture, MotionCommand.ACCELERATE));
		assertTrue(TrainMotion.applyCommand(fixture, MotionCommand.ACCELERATE));
		assertFalse(TrainMotion.applyCommand(fixture, MotionCommand.ACCELERATE));
		assertEquals(2, fixture.manualNotch);
		for (int i = 0; i < 4; i++) {
			assertTrue(TrainMotion.applyCommand(fixture, MotionCommand.BRAKE));
		}
		assertFalse(TrainMotion.applyCommand(fixture, MotionCommand.BRAKE));
		assertEquals(-2, fixture.manualNotch);
		// No speeding up with the doors open
		fixture.doorValue = 0.5F;
		assertFalse(TrainMotion.applyCommand(fixture, MotionCommand.ACCELERATE));
	}

	@Test
	void doorsOnlyWhileStanding() {
		final MotionFixture fixture = new MotionFixture();
		fixture.manualNotch = 1;
		assertTrue(TrainMotion.applyCommand(fixture, MotionCommand.TOGGLE_DOORS));
		assertTrue(fixture.doorTarget);
		assertEquals(-2, fixture.manualNotch);
		fixture.speed = 0.2F;
		assertFalse(TrainMotion.applyCommand(fixture, MotionCommand.TOGGLE_DOORS));
		assertFalse(fixture.doorTarget);
	}

	private static void run(MotionFixture fixture, int ticks) {
		for (int i = 0; i < ticks; i++) {
			fixture.step(false, 1);
		}
	}

	private static String describe(List<MotionEvent> events) {
		return events.stream().map(MotionEvent::toString).collect(Collectors.joining("\n"));
	}

	private static MotionFixture line() {
		final MotionFixture fixture = new MotionFixture()
				.rail(30, 0, 0.3F)
				.rail(300, 0, 0.6F)
				.rail(60, 2, 0.3F, false, false)
				.rail(400, 0, 0.6F)
				.rail(60, 2, 0.3F, false, false)
				.rail(200, 0, 0.4F);
		fixture.speedCurve = true;
		return fixture;
	}
}
