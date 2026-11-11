package transitsim.sim;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PlaybackGateTest {
	@Test void waitsForEndAndGapWithoutQueuing() {
		PlaybackGate gate = new PlaybackGate();
		assertTrue(gate.accept(10, 100, true, 20));
		assertFalse(gate.accept(109, 100, true, 20));
		assertFalse(gate.accept(129, 100, true, 20));
		assertTrue(gate.accept(130, 100, true, 20));
	}
	@Test void interruptionUsesOnlyGapAndResetClearsIt() {
		PlaybackGate gate = new PlaybackGate();
		assertTrue(gate.accept(0, 100, false, 20));
		assertFalse(gate.accept(19, 100, false, 20));
		assertTrue(gate.accept(20, 100, false, 20));
		gate.reset();
		assertTrue(gate.accept(0, 100, false, 0));
		assertTrue(gate.accept(0, 100, false, 0));
	}
}
