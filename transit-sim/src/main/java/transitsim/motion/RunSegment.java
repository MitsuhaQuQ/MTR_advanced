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
	/**
	 * Where it set off from and how fast, which with the run, cap and target make the whole curve (G2).
	 */
	public final double startPosition;
	public final double startSpeed;
	private final SpeedProfile profile;
	private double elapsedTicks;

	private RunSegment(int version, SpeedCurves.Run run, double cap, double target, boolean emergency, double startPosition, double startSpeed, SpeedProfile profile) {
		this.version = version;
		this.run = run;
		this.cap = cap;
		this.target = target;
		this.emergency = emergency;
		this.startPosition = startPosition;
		this.startSpeed = startSpeed;
		this.profile = profile;
	}

	/**
	 * @param previous the segment this one replaces, or null
	 * @param target   where the train has to stop, no further than the end of the run
	 */
	public static RunSegment start(RunSegment previous, SpeedCurves.Run run, double position, double speed, double cap, double target) {
		return start(previous == null ? 1 : previous.version + 1, run, position, speed, cap, target);
	}

	private static RunSegment start(int version, SpeedCurves.Run run, double position, double speed, double cap, double target) {
		final double deceleration = run.getDeceleration();
		final double distance = Math.max(0, target - position);
		final boolean emergency = speed * speed > 2 * deceleration * distance + POSITION_TOLERANCE;
		final SpeedProfile profile = run.fromTo(position, speed, cap, target, emergency ? deceleration * EMERGENCY_DECELERATION_FACTOR : deceleration);
		return new RunSegment(version, run, cap, target, emergency, position, speed, profile);
	}

	/**
	 * @param nowMillis the host's time now, in its ticks' milliseconds
	 * @return what makes the segment, to send to someone who has the same speed curves (G2)
	 */
	public Spec describe(long nowMillis) {
		return new Spec(version, nowMillis - Math.round(elapsedTicks * MotionConstants.MILLIS_PER_TICK), startPosition, startSpeed, cap, target);
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

	/**
	 * @return where the train is this many ticks after the segment started, without moving it
	 */
	public double getPositionAt(double ticks) {
		return profile.getPosition(Math.max(0, ticks));
	}

	/**
	 * @return how fast the train goes this many ticks after the segment started, 0 once it has stopped
	 */
	public double getSpeedAt(double ticks) {
		return ticks >= profile.getDuration() ? 0 : profile.getSpeed(getPositionAt(ticks));
	}

	/**
	 * @return where the train will be after this many more ticks on the segment, without moving it
	 */
	public double getPositionAfter(double ticks) {
		return profile.getPosition(elapsedTicks + ticks);
	}

	/**
	 * @return how fast the train will be going after this many more ticks on the segment
	 */
	public double getSpeedAfter(double ticks) {
		return profile.getSpeed(getPositionAfter(ticks));
	}

	/**
	 * @return how long the whole segment takes, in ticks
	 */
	public double getDuration() {
		return profile.getDuration();
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

	/**
	 * What makes a segment, as a host sends it to someone who has the same speed curves (G2): its version, when it started,
	 * where from and how fast, its cap and where it stops. With the run there, {@link #rebuild} makes the same curve.
	 */
	public static final class Spec {

		public final int version;
		/**
		 * When the segment started, by the host's clock in milliseconds.
		 */
		public final long startMillis;
		public final double startPosition;
		public final double startSpeed;
		public final double cap;
		public final double target;

		public Spec(int version, long startMillis, double startPosition, double startSpeed, double cap, double target) {
			this.version = version;
			this.startMillis = startMillis;
			this.startPosition = startPosition;
			this.startSpeed = startSpeed;
			this.cap = cap;
			this.target = target;
		}

		/**
		 * @param run the run of the speed curves that has the start position, as {@link MotionCurves#getRun} finds it
		 */
		public RunSegment rebuild(SpeedCurves.Run run) {
			return start(version, run, startPosition, startSpeed, cap, target);
		}

		/**
		 * @return how many ticks the segment has run by this time of the host's clock
		 */
		public double getTicksAt(long millis) {
			return (double) (millis - startMillis) / MotionConstants.MILLIS_PER_TICK;
		}

		@Override
		public boolean equals(Object object) {
			if (!(object instanceof Spec)) {
				return false;
			}
			final Spec spec = (Spec) object;
			return version == spec.version && startMillis == spec.startMillis && startPosition == spec.startPosition && startSpeed == spec.startSpeed && cap == spec.cap && target == spec.target;
		}

		@Override
		public int hashCode() {
			return Long.hashCode(startMillis) * 31 + version;
		}
	}
}
