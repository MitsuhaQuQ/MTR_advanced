package transitsim.sim;

import java.util.function.Supplier;

/**
 * What the simulation says as trains run (a duty swapped, a duty not run, a spare train missing), printed for the host's
 * log. A run that only looks ahead, such as a rehearsal of a plan (0.83.4), runs quietly: its trains are not the game's,
 * and its many lines buried what the game's own trains did.
 */
public final class SimLog {

	private static final ThreadLocal<Boolean> QUIET = ThreadLocal.withInitial(() -> false);

	private SimLog() {
	}

	/**
	 * Prints a line unless this thread runs quietly
	 */
	public static void say(String text) {
		if (!QUIET.get()) {
			System.out.println(text);
		}
	}

	/**
	 * @return whether what runs on this thread now says nothing
	 */
	public static boolean isQuiet() {
		return QUIET.get();
	}

	/**
	 * Runs something with nothing it says printed, then says again as before
	 */
	public static <T> T quietly(Supplier<T> run) {
		final boolean before = QUIET.get();
		QUIET.set(true);
		try {
			return run.get();
		} finally {
			QUIET.set(before);
		}
	}
}
