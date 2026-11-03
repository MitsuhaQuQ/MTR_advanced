package transitsim.sim;

import transitsim.network.RailKeys;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps trains running by duties from meeting head on (design document 6.12, block E2). Before a train leaves a stop it
 * books, with its direction, the two way rails of its run to the next stop; it waits at the stop while any of them is booked
 * the other way. Trains going the same way don't hold each other up, which the signal blocks look after. Bookings are kept
 * only while the train renews them every tick, so a train that is gone or has changed its path drops them by itself.
 */
public final class RunReservations {

	private Map<UUID, Booking> current = new HashMap<>();
	private Map<UUID, Booking> next = new HashMap<>();

	/**
	 * Starts a new tick: what was booked during the last one is what counts now.
	 */
	public void tick() {
		current = next;
		next = new HashMap<>();
	}

	/**
	 * @return false if another train has booked one of these rails the other way
	 */
	public boolean canBook(long trainId, List<Move> moves) {
		for (final Move move : moves) {
			if (isBookedAgainst(current.get(move.rail), trainId, move.forwards) || isBookedAgainst(next.get(move.rail), trainId, move.forwards)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Books or renews rails for this tick. A rail another train has already booked the same way stays booked to that train,
	 * which blocks the other way just the same, and passes to this one once that train stops renewing it.
	 */
	public void book(long trainId, List<Move> moves) {
		moves.forEach(move -> {
			final Booking booking = next.get(move.rail);
			if (booking == null || booking.trainId == trainId) {
				next.put(move.rail, new Booking(trainId, move.forwards));
			}
		});
	}

	private static boolean isBookedAgainst(Booking booking, long trainId, boolean forwards) {
		return booking != null && booking.trainId != trainId && booking.forwards != forwards;
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

	private static final class Booking {

		private final long trainId;
		private final boolean forwards;

		private Booking(long trainId, boolean forwards) {
			this.trainId = trainId;
			this.forwards = forwards;
		}
	}
}
