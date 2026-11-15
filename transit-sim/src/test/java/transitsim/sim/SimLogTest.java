package transitsim.sim;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A rehearsal runs quietly (0.83.4): what its simulated trains do isn't printed into the game's log.
 */
class SimLogTest {

	@Test
	void aQuietRunSaysNothingAndThenSaysAgain() {
		final PrintStream before = System.out;
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		System.setOut(new PrintStream(out, true));
		try {
			SimLog.say("one");
			final String result = SimLog.quietly(() -> {
				assertTrue(SimLog.isQuiet());
				SimLog.say("two");
				return "done";
			});
			assertEquals("done", result);
			assertFalse(SimLog.isQuiet());
			SimLog.say("three");
		} finally {
			System.setOut(before);
		}
		final String text = out.toString().replace("\r", "");
		assertEquals("one\nthree\n", text);
	}

	@Test
	void anotherThreadSaysWhileOneRunsQuietly() throws InterruptedException {
		final boolean[] quietThere = {true};
		SimLog.quietly(() -> {
			final Thread other = new Thread(() -> quietThere[0] = SimLog.isQuiet());
			other.start();
			try {
				other.join();
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			return null;
		});
		assertFalse(quietThere[0]);
	}
}
