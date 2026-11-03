package transitsim.sim;

import transitsim.network.RailKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps trains running by duties from meeting head on (design document 6.12, block E2, and the fixes F1 for R01 to R03 of
 * the architecture review). A train holds, with its direction, the two way rails from its tail to the end of the run it
 * was given, and while any of them is held the other way no other train is given a run over it. Trains going the same way
 * don't hold each other up, which the signal blocks look after.
 * <p>
 * Holding is not given up by stopping: a train waiting at a stop or held by a signal between stops keeps what it holds.
 * The host sets what each train holds every tick, so a rail is let go only once the train's tail has left it, and a train
 * that is gone, so no longer sets anything, gives up all of it when the tick is over.
 * <p>
 * Trains ready to leave a stop ask for the rails of their next run. The requests of a tick are dealt with together once it
 * is over, in a fixed order, so the result doesn't depend on the order the trains ran in: requests that have waited
 * {@link #AGING_MILLIS} or longer first, longest waiting first, then the rest by planned departure, then by train. While a
 * request that has waited that long can't be granted, no train that isn't on its rails yet is let onto them the other way,
 * so the trains there clear out and a stream of trains the other way can't keep it waiting for ever.
 */
public final class RunReservations {

	/**
	 * Each two way rail is a single track resource of the arbiter (S4b), with its direction.
	 */
	private final Arbiter<UUID> arbiter = new Arbiter<>();

	/**
	 * How long a train may wait for its run before it goes ahead of trains due to leave earlier.
	 */
	public static final long AGING_MILLIS = Arbiter.AGING_MILLIS;

	/**
	 * Sets what a train holds this tick, replacing what it held before.
	 */
	public void hold(long trainId, List<Move> moves) {
		arbiter.hold(trainId, toClaims(moves));
	}

	/**
	 * Gives up everything a train holds, as when it is back in its depot.
	 */
	public void release(long trainId) {
		arbiter.release(trainId);
	}

	/**
	 * @return false if another train holds one of these rails the other way
	 */
	public boolean canHold(long trainId, List<Move> moves) {
		return arbiter.canHold(trainId, toClaims(moves));
	}

	/**
	 * Asks for the rails of a train's next run, which are given or not once the tick is over. A train still waiting asks
	 * again every tick; one that stops asking is dropped.
	 *
	 * @param plannedMillis when the train is planned to leave
	 */
	public void request(long trainId, List<Move> moves, long plannedMillis) {
		arbiter.request(trainId, toClaims(moves), plannedMillis);
	}

	/**
	 * @return true once, the tick after the train's request was granted, when it holds the rails it asked for
	 */
	public boolean takeGrant(long trainId) {
		return arbiter.takeGrant(trainId);
	}

	/**
	 * Ends a tick: trains gone since the last one give up what they held, then the requests of this tick are dealt with.
	 */
	public void resolve(long currentMillis) {
		arbiter.resolve(currentMillis);
	}

	/**
	 * @return the trains of each deadlock found since this was last called, each once
	 */
	public List<Set<Long>> takeDeadlocks() {
		return arbiter.takeDeadlocks();
	}

	private static List<Arbiter.Claim<UUID>> toClaims(List<Move> moves) {
		final List<Arbiter.Claim<UUID>> claims = new ArrayList<>();
		moves.forEach(move -> claims.add(Arbiter.Claim.singleTrack(move.rail, move.forwards)));
		return claims;
	}

	/**
	 * A rail run along in one direction. Forwards is from the lower node of the rail's key to the higher.
	 */
	public static final class Move {

		public final UUID rail;
		public final boolean forwards;

		public Move(long fromPos, long toPos) {
			rail = RailKeys.of(fromPos, toPos);
			forwards = fromPos < toPos;
		}
	}

}
