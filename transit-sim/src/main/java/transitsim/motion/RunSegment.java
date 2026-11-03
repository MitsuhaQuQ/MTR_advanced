package transitsim.motion;

/**
 * A train running from where it set off to where it has to stop (decoupling document 3.5): a speed curve and the time
 * since it started, in ticks of the host. The train's position and speed are the curve's at that time, so they don't
 * depend on how the time was cut into steps (contract 2.1, rule 6).
 * <p>
 * A segment is replaced, never changed, when the place to stop moves, the run or its cruising cap changes, or the train
 * is moved by something else. The new one starts from where the train is and how fast it is going (review A04), and only
 * what lies ahead changes. Its version is one more than the one it replaced.
 */
public final class RunSegment {

	/**
	 * How much harder than its own a train brakes when it can't otherwise stop in time.
	 */
	public static final double EMERGENCY_DECELERATION_FACTOR = 1.5;
	/**
	 * How far the train may be from where the segment has it before the segment counts as left behind.
	 */
	public static final double POSITION_TOLERANCE = 1E-6;

	public final int version;
	public final SpeedCurves.Run run;
	public final double cap;
	/**
	 * Where the train stops at the end of the segment.
	 */
	public final double target;
	/**
	 * True if the train couldn't stop in time with its own braking, so it braked harder, or even that wasn't enough.
	 */
	public final boolean emergency;
	private final SpeedProfile profile;
	private double elapsedTicks;

	private RunSegment(int version, SpeedCurves.Run run, double cap, double target, boolean emergency, SpeedProfile profile) {
		this.version = version;
		this.run = run;
		this.cap = cap;
		this.target = target;
		this.emergency = emergency;
		this.profile = profile;
	}

	/**
	 * @param previous the segment this one replaces, or null
	 * @param target   where the train has to stop, no further than the end of the run
	 */
	public static RunSegment start(RunSegment previous, SpeedCurves.Run run, double position, double speed, double cap, double target) {
		final double deceleration = run.getDeceleration();
		final double distance = Math.max(0, target - position);
		final boolean emergency = speed * speed > 2 * deceleration * distance + POSITION_TOLERANCE;
		final SpeedProfile profile = run.fromTo(position, speed, cap, target, emergency ? deceleration * EMERGENCY_DECELERATION_FACTOR : deceleration);
		return new RunSegment(previous == null ? 1 : previous.version + 1, run, cap, target, emergency, profile);
	}

	public void advance(double ticks) {
		if (ticks > 0) {
			elapsedTicks += ticks;
		}
	}

	public double getPosition() {
		return profile.getPosition(elapsedTicks);
	}

	public double getSpeed() {
		return profile.getSpeed(getPosition());
	}

	public double getElapsedTicks() {
		return elapsedTicks;
	}

	public boolean isFinished() {
		return elapsedTicks >= profile.getDuration();
	}

	/**
	 * Whether the segment still describes this train: the same run, cap and place to stop, and the train where it says.
	 */
	public boolean matches(SpeedCurves.Run run, double cap, double target, double position) {
		return this.run == run && this.cap == cap && this.target == target && Math.abs(getPosition() - position) <= POSITION_TOLERANCE;
	}
}
