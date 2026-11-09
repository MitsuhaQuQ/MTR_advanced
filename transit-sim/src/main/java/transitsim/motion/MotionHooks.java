package transitsim.motion;

/**
 * Where the motion rules ask the host or tell it something. These were the overridable methods of the host's train
 * (decoupling document 2.2), and are called in the same order as before.
 */
public interface MotionHooks {

	/**
	 * A client only follows the train: it doesn't check the rails ahead or start it from a stop.
	 */
	boolean isClientSide();

	/**
	 * How long the train stops at its next stop, in ticks, which can be shorter for a late train.
	 */
	int getTotalDwellTicks();

	/**
	 * Whether a train waiting in its siding may leave now.
	 */
	boolean canDeploy();

	/**
	 * Starts the train from a stop, or from its siding. The host sets the speed, the next stop and turns the train round
	 * if it has to.
	 */
	void startUp(boolean isOppositeRail);

	/**
	 * Whether the end of the path is a stop on the line rather than the way into the siding.
	 */
	boolean keepsOnLineAtEnd();

	/**
	 * The train has gone back into its siding, so nobody rides it any more.
	 */
	void clearRiders();

	boolean isRepeat();

	/**
	 * Called as a repeating train starts its next lap, before it looks for its next stop.
	 */
	void onLapComplete();

	/**
	 * Called while a train has just stopped, before its stop time starts counting, which can be more than once.
	 */
	void onStopReached();

	/** Actual dwell at a stop, or rail -1 once no longer there. Negative remaining ticks means departure is unconfirmed. */
	default void onDwell(int rail, float departureTicks) {
	}

	/**
	 * While true, a stopped train keeps its doors open and doesn't leave.
	 */
	boolean isHeldAtStop();

	boolean isRailBlocked(int rail);

	/**
	 * For a rail cut into block sections ({@code BlockSections}): where on the path the first section of it at or after this
	 * position that another train has starts, so the train can follow as far as that. A rail in one section, or with none of
	 * its sections taken ahead, gives NaN, and {@link #isRailBlocked} says whether it is taken as a whole.
	 *
	 * @param fromPosition a position on the path, as the train's rail progress counts it
	 */
	default double getSectionBlockedPosition(int rail, double fromPosition) {
		return Double.NaN;
	}

	boolean openDoors();

	/**
	 * Something happened to the train. The host adds when and which train, and collects the events at the end of the tick.
	 * Only the new rules on the server have events.
	 */
	void emit(MotionEvent.Type type, int rail);
}
