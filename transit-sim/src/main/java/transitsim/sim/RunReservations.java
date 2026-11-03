package transitsim.sim;

import transitsim.network.RailKeys;

import java.util.*;

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

	private final Map<Long, List<Move>> holdings = new HashMap<>();
	private final Map<UUID, Map<Long, Boolean>> holders = new HashMap<>();
	private final Set<Long> heldThisTick = new HashSet<>();
	private final Map<Long, Request> requests = new HashMap<>();
	private final Map<Long, Long> firstRequestMillis = new HashMap<>();
	private final Set<Long> grants = new HashSet<>();

	/**
	 * How long a train may wait for its run before it goes ahead of trains due to leave earlier.
	 */
	public static final long AGING_MILLIS = 60000;

	/**
	 * Sets what a train holds this tick, replacing what it held before.
	 */
	public void hold(long trainId, List<Move> moves) {
		removeHoldings(trainId);
		heldThisTick.add(trainId);
		if (!moves.isEmpty()) {
			holdings.put(trainId, new ArrayList<>(moves));
			moves.forEach(move -> holders.computeIfAbsent(move.rail, key -> new HashMap<>()).put(trainId, move.forwards));
		}
	}

	/**
	 * Gives up everything a train holds, as when it is back in its depot.
	 */
	public void release(long trainId) {
		removeHoldings(trainId);
		heldThisTick.remove(trainId);
	}

	/**
	 * @return false if another train holds one of these rails the other way
	 */
	public boolean canHold(long trainId, List<Move> moves) {
		for (final Move move : moves) {
			final Map<Long, Boolean> railHolders = holders.get(move.rail);
			if (railHolders != null) {
				for (final Map.Entry<Long, Boolean> entry : railHolders.entrySet()) {
					if (entry.getKey() != trainId && entry.getValue() != move.forwards) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/**
	 * Asks for the rails of a train's next run, which are given or not once the tick is over. A train still waiting asks
	 * again every tick; one that stops asking is dropped.
	 *
	 * @param plannedMillis when the train is planned to leave
	 */
	public void request(long trainId, List<Move> moves, long plannedMillis) {
		requests.put(trainId, new Request(trainId, new ArrayList<>(moves), plannedMillis));
	}

	/**
	 * @return true once, the tick after the train's request was granted, when it holds the rails it asked for
	 */
	public boolean takeGrant(long trainId) {
		return grants.remove(trainId);
	}

	/**
	 * Ends a tick: trains gone since the last one give up what they held, then the requests of this tick are dealt with.
	 */
	public void resolve(long currentMillis) {
		new ArrayList<>(holdings.keySet()).forEach(trainId -> {
			if (!heldThisTick.contains(trainId)) {
				removeHoldings(trainId);
			}
		});
		grants.clear();
		firstRequestMillis.keySet().retainAll(requests.keySet());

		final List<Request> waiting = new ArrayList<>(requests.values());
		waiting.forEach(request -> firstRequestMillis.putIfAbsent(request.trainId, currentMillis));
		waiting.sort((request1, request2) -> {
			final long first1 = firstRequestMillis.get(request1.trainId);
			final long first2 = firstRequestMillis.get(request2.trainId);
			final boolean aged1 = currentMillis - first1 >= AGING_MILLIS;
			final boolean aged2 = currentMillis - first2 >= AGING_MILLIS;
			if (aged1 != aged2) {
				return aged1 ? -1 : 1;
			}
			final int byTime = aged1 ? Long.compare(first1, first2) : Long.compare(request1.plannedMillis, request2.plannedMillis);
			return byTime != 0 ? byTime : Long.compare(request1.trainId, request2.trainId);
		});

		// The rails a request that has waited too long is still kept from, with the way it asked for them. No new train is let
		// onto them the other way, so the trains already there can clear out and a steady stream can't starve the request.
		final Map<UUID, Boolean> claims = new HashMap<>();
		for (final Request request : waiting) {
			if (canHold(request.trainId, request.moves) && (holdsAny(request.trainId, claims) || !isAgainstClaims(request.moves, claims))) {
				hold(request.trainId, request.moves);
				grants.add(request.trainId);
				firstRequestMillis.remove(request.trainId);
			} else if (currentMillis - firstRequestMillis.get(request.trainId) >= AGING_MILLIS) {
				request.moves.forEach(move -> claims.putIfAbsent(move.rail, move.forwards));
			}
		}
		requests.clear();
		// Every train sets what it holds again next tick, including what was just granted
		heldThisTick.clear();
	}

	/**
	 * @return true if the train already holds one of these rails, so it is among the trains to let clear out
	 */
	private boolean holdsAny(long trainId, Map<UUID, Boolean> claims) {
		final List<Move> moves = holdings.get(trainId);
		if (moves != null) {
			for (final Move move : moves) {
				if (claims.containsKey(move.rail)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean isAgainstClaims(List<Move> moves, Map<UUID, Boolean> claims) {
		for (final Move move : moves) {
			final Boolean forwards = claims.get(move.rail);
			if (forwards != null && forwards != move.forwards) {
				return true;
			}
		}
		return false;
	}

	private void removeHoldings(long trainId) {
		final List<Move> moves = holdings.remove(trainId);
		if (moves != null) {
			moves.forEach(move -> {
				final Map<Long, Boolean> railHolders = holders.get(move.rail);
				if (railHolders != null) {
					railHolders.remove(trainId);
					if (railHolders.isEmpty()) {
						holders.remove(move.rail);
					}
				}
			});
		}
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

	private static final class Request {

		private final long trainId;
		private final List<Move> moves;
		private final long plannedMillis;

		private Request(long trainId, List<Move> moves, long plannedMillis) {
			this.trainId = trainId;
			this.moves = moves;
			this.plannedMillis = plannedMillis;
		}
	}
}
