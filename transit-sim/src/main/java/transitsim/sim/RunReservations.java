package transitsim.sim;

import transitsim.network.RailKeys;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
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
	/**
	 * @return whether the train kept another train from being given what it asked for, at the last resolve
	 */
	public boolean isBlockingOthers(long trainId) {
		return arbiter.isBlocking(trainId);
	}

	public void hold(long trainId, List<Move> moves) {
		arbiter.hold(trainId, toClaims(moves));
	}

	/**
	 * Sets what a train holds this tick as the run layer asks for it (5.5): sections, directions, turnouts and zones.
	 */
	public void holdClaims(long trainId, List<Arbiter.Claim<UUID>> claims) {
		arbiter.hold(trainId, claims);
	}

	/**
	 * Asks for what a train is to hold from the next tick, granted whole or not at all, as the run layer asks for it.
	 */
	public void requestClaims(long trainId, List<Arbiter.Claim<UUID>> claims, long plannedMillis) {
		arbiter.request(trainId, claims, plannedMillis);
	}

	/**
	 * What something the run layer doesn't move stands on now, such as rails taken from outside or a train the host drives,
	 * in place of what it stood on: no train is granted those resources.
	 */
	public void setOccupied(long occupant, Collection<UUID> resources) {
		arbiter.setOccupied(occupant, resources);
	}

	/**
	 * Asks for groups of resources in the order the train reaches them, as many of the first granted as can be, each whole.
	 */
	public void requestGroups(long trainId, List<List<Arbiter.Claim<UUID>>> groups, long plannedMillis) {
		arbiter.requestGroups(trainId, groups, plannedMillis);
	}

	/**
	 * @return whether the train could hold these resources now, nothing else holding or standing on them against it
	 */
	public boolean canHoldClaims(long trainId, List<Arbiter.Claim<UUID>> claims) {
		return arbiter.canHold(trainId, claims);
	}

	public int peekGrantedGroups(long trainId) {
		return arbiter.peekGrantedGroups(trainId);
	}

	/**
	 * @return how many groups of the train's last request of groups were granted, once
	 */
	public int takeGrantedGroups(long trainId) {
		return arbiter.takeGrantedGroups(trainId);
	}

	/**
	 * @return whether another train holds this resource, any way
	 */
	public boolean isHeldByOther(long trainId, UUID resource) {
		return arbiter.isHeldByOther(trainId, resource);
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
	 * @return the trains holding rails, in order
	 */
	public Set<Long> getHolders() {
		return arbiter.getHolders();
	}

	/**
	 * @return whether another train holds one of these rails, either way, or a turnout at a node between two of them
	 */
	public boolean isHeldByOther(long trainId, List<UUID> rails) {
		final Set<UUID> railSet = new HashSet<>(rails);
		final Set<Long> nodes = new HashSet<>();
		rails.forEach(rail -> {
			nodes.add(rail.getMostSignificantBits());
			nodes.add(rail.getLeastSignificantBits());
		});
		for (final long holder : getHolders()) {
			if (holder == trainId) {
				continue;
			}
			for (final Move move : getHeld(holder)) {
				if (move.turnout ? nodes.contains(move.rail.getMostSignificantBits()) : !move.zone && railSet.contains(move.rail)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * @return the rails a train holds, with their directions
	 */
	/**
	 * @return what the arbiter has granted a train, for debugging deadlocks (simulateDay -Pmtr.debugStuck=all)
	 */
	public List<Arbiter.Claim<UUID>> getHeldClaims(long trainId) {
		return arbiter.getHoldings(trainId);
	}

	public List<Move> getHeld(long trainId) {
		final List<Move> moves = new ArrayList<>();
		arbiter.getHoldings(trainId).forEach(claim -> {
			if (claim.kind == transitsim.network.Resource.Kind.SINGLE_TRACK && claim.way == 0) {
				moves.add(new Move(claim.resource, true));
				moves.add(new Move(claim.resource, false));
			} else {
				moves.add(claim.kind == transitsim.network.Resource.Kind.TRACK ? Move.zoneOf(claim.resource) : claim.kind == transitsim.network.Resource.Kind.TURNOUT ? new Move(claim.resource, claim.way) : new Move(claim.resource, claim.way > 0));
			}
		});
		return moves;
	}

	/**
	 * @return the trains of each deadlock found since this was last called, each once
	 */
	public List<Set<Long>> takeDeadlocks() {
		return arbiter.takeDeadlocks();
	}

	/**
	 * @return the arbiter's claims of some moves: rails with their direction, turnouts with their way, zones
	 */
	public static List<Arbiter.Claim<UUID>> claimsOf(List<Move> moves) {
		return toClaims(moves);
	}

	private static List<Arbiter.Claim<UUID>> toClaims(List<Move> moves) {
		// A rail asked for both ways by the same train, as a stop it turns back at, is one claim of it both ways: a train
		// holds one claim of each rail, so the second way would otherwise take the place of the first
		final java.util.Map<UUID, Integer> singleTracks = new java.util.HashMap<>();
		final List<Arbiter.Claim<UUID>> claims = new ArrayList<>();
		moves.forEach(move -> {
			if (move.zone) {
				claims.add(Arbiter.Claim.track(move.rail));
			} else if (move.turnout) {
				claims.add(Arbiter.Claim.turnout(move.rail, move.way));
			} else {
				final Integer index = singleTracks.get(move.rail);
				if (index == null) {
					singleTracks.put(move.rail, claims.size());
					claims.add(Arbiter.Claim.singleTrack(move.rail, move.forwards));
				} else if (claims.get(index).way != (move.forwards ? 1 : -1)) {
					claims.set(index, Arbiter.Claim.singleTrackBothWays(move.rail));
				}
			}
		});
		return claims;
	}

	/**
	 * A rail run along in one direction, or a turnout of a station's throat taken by one way through it (L2,
	 * {@link ThroatLocks}). Forwards is from the lower node of the rail's key to the higher.
	 */
	public static final class Move {

		/**
		 * Turnouts are kept apart from rails by this in the low half of their key, which a rail's key never has.
		 */
		private static final long TURNOUT_MARK = 0x7475726E6F757421L;
		/**
		 * Interlocking zones the same way, in the low half of their key.
		 */
		private static final long ZONE_MARK = 0x7A6F6E6521212121L;

		public final UUID rail;
		public final boolean forwards;
		public final boolean turnout;
		/**
		 * An interlocking zone, which one train has at a time whichever way (L2, signal blocks of the interlocking colour).
		 */
		public final boolean zone;
		/**
		 * For a turnout, which way through it; trains the same way share it.
		 */
		public final int way;

		public Move(long fromPos, long toPos) {
			rail = RailKeys.of(fromPos, toPos);
			forwards = fromPos < toPos;
			turnout = false;
			zone = false;
			way = 0;
		}

		public Move(UUID rail, boolean forwards) {
			this.rail = rail;
			this.forwards = forwards;
			turnout = false;
			zone = false;
			way = 0;
		}

		private Move(UUID resource, int way) {
			rail = resource;
			forwards = true;
			turnout = true;
			zone = false;
			this.way = way;
		}

		private Move(UUID resource) {
			rail = resource;
			forwards = true;
			turnout = false;
			zone = true;
			way = 0;
		}

		/**
		 * @return the whole of an interlocking zone, by its ID
		 */
		public static Move zone(long zoneId) {
			return new Move(new UUID(zoneId, ZONE_MARK));
		}

		private static Move zoneOf(UUID resource) {
			return new Move(resource);
		}

		/**
		 * @return a turnout taken by the way between the two nodes beside it, in either order
		 */
		public static Move turnout(long node, long side1, long side2) {
			return new Move(new UUID(node, TURNOUT_MARK), java.util.Objects.hash(Math.min(side1, side2), Math.max(side1, side2)) | 1);
		}

		@Override
		public boolean equals(Object object) {
			return object instanceof Move && ((Move) object).rail.equals(rail) && ((Move) object).forwards == forwards && ((Move) object).turnout == turnout && ((Move) object).zone == zone && ((Move) object).way == way;
		}

		@Override
		public int hashCode() {
			return rail.hashCode() * 2 + (forwards ? 1 : 0) + way * 31;
		}
	}

}
