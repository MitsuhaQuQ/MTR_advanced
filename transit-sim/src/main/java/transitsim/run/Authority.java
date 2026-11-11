package transitsim.run;

import transitsim.network.BlockSections;
import transitsim.sim.Arbiter;
import transitsim.sim.RunReservations;
import transitsim.sim.RunTrack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A train's movement authority (run layer, decoupling document 5.5): how far along its path it may run. Every tick it holds
 * what its body stands on and what it was granted ahead, and asks for the next group of resources towards where it is
 * going. The arbiter grants a group whole or not at all, at the end of the tick, so the authority grows group by group
 * as the way ahead clears. Nothing is held unchecked beyond the train's body.
 */
public final class Authority {

	private final long holder;
	private final java.util.function.Supplier<RunReservations> reservations;
	/**
	 * How far along the path the train has been granted, and on which path.
	 */
	private double grantedEnd = Double.NaN;
	private Object pathKey;
	private PathClaims.Group pending;
	/**
	 * The groups asked for this tick, in order.
	 */
	private final List<PathClaims.Group> asked = new ArrayList<>();
	/**
	 * How far it reached in the record it carries on from, or NaN.
	 */
	private double restoredEnd = Double.NaN;
	private List<Arbiter.Claim<UUID>> held = new ArrayList<>();

	public Authority(long holder, RunReservations reservations) {
		this(holder, () -> reservations);
	}

	/**
	 * @param reservations the reservations of now, as the host may share its own with the runner
	 */
	public Authority(long holder, java.util.function.Supplier<RunReservations> reservations) {
		this.holder = holder;
		this.reservations = reservations;
	}

	/**
	 * Holds the body and what was granted ahead, and asks for the next group towards {@code target}.
	 *
	 * @param pathKey       the path the positions are along; another one starts the authority afresh
	 * @param tail          where the train's tail is
	 * @param head          where its head is
	 * @param target        how far it wants to go: its next stop, or the next one after the stop it stands at
	 * @param plannedMillis when it is planned to leave, which decides who goes first when two ask at once
	 * @return how far the train may run now
	 */
	public <T extends RunTrack> double update(PathClaims.Path<T> path, Object pathKey, double tail, double head, double target, BlockSections sections, long plannedMillis) {
		return update(path, pathKey, tail, head, target, sections, plannedMillis, java.util.Collections.emptyList());
	}

	/**
	 * @param attached turnouts and zones of stations' throats along the path, held while the train needs them and asked
	 *                 for with the group taking it past each
	 */
	public <T extends RunTrack> double update(PathClaims.Path<T> path, Object pathKey, double tail, double head, double target, BlockSections sections, long plannedMillis, List<PathClaims.Attached> attached) {
		boolean fresh = pathKey != this.pathKey || Double.isNaN(grantedEnd);
		if (fresh) {
			this.pathKey = pathKey;
			grantedEnd = head;
			asked.clear();
			if (!Double.isNaN(restoredEnd)) {
				// Carrying on from a record: what it held then, as it held it
				grantedEnd = Math.max(head, restoredEnd);
				fresh = false;
			}
		}
		restoredEnd = Double.NaN;
		// The groups granted at the end of the last tick
		final int granted = reservations.get().takeGrantedGroups(holder);
		if (granted > 0 && granted <= asked.size()) {
			grantedEnd = Math.max(grantedEnd, asked.get(granted - 1).end);
		}
		asked.clear();
		pending = null;
		grantedEnd = target <= head + 1E-6 ? head : Math.max(grantedEnd, head);
		final List<PathClaims.Group> keep = new ArrayList<>(PathClaims.between(path, tail, head, sections));
		// What is attached behind the head and not left by the tail yet is held with the body
		final List<PathClaims.Attached> aheadAttached = new ArrayList<>();
		for (final PathClaims.Attached each : attached) {
			if (each.start <= head + 1E-6 && each.end >= tail - 1E-6) {
				keep.add(new PathClaims.Group(each.claims, head));
			} else if (each.start > head + 1E-6) {
				aheadAttached.add(each);
			}
		}
		final List<PathClaims.Group> ahead = target <= head + 1E-6 ? java.util.Collections.emptyList() : PathClaims.attach(PathClaims.between(path, head, target, sections), aheadAttached);
		if (fresh) {
			// Starting afresh, after a restart or onto another path, the train takes at once what it can of the way ahead, as
			// it held it going on: so carrying on from a record runs as running on does
			final List<Arbiter.Claim<UUID>> taking = new ArrayList<>(PathClaims.claimsOf(keep));
			for (final PathClaims.Group group : ahead) {
				final List<Arbiter.Claim<UUID>> more = new ArrayList<>(taking);
				more.addAll(group.claims);
				if (!reservations.get().canHoldClaims(holder, group.claims) || !isClear(group)) {
					break;
				}
				taking.clear();
				taking.addAll(PathClaims.claimsOf(java.util.Collections.singletonList(new PathClaims.Group(more, group.end))));
				grantedEnd = Math.max(grantedEnd, group.end);
			}
		}
		for (final PathClaims.Group group : ahead) {
			if (pending == null && group.end <= grantedEnd + 1E-6 && isClear(group)) {
				keep.add(group);
			} else {
				if (pending == null) {
					// Granted but now stood on by something else, as rails taken from outside: the authority is cut back to
					// it, as a signal goes back to red, since the train is not on it yet
					grantedEnd = Math.min(grantedEnd, Math.max(head, group.start));
					pending = group;
				}
				asked.add(group);
			}
		}
		held = PathClaims.claimsOf(keep);
		reservations.get().holdClaims(holder, held);
		if (!asked.isEmpty()) {
			// The rest in order: as many as can be are granted at once, so the authority depends on what is clear now, not on
			// how many ticks the train has asked
			final List<List<Arbiter.Claim<UUID>>> groups = new ArrayList<>();
			asked.forEach(group -> groups.add(group.claims));
			reservations.get().requestGroups(holder, groups, plannedMillis);
		}
		// Up to where the next group starts: tracks that are not sections, as the siding, need nothing; with nothing more to
		// ask for, all the way
		final double reach = pending == null ? Math.max(head, target) : Math.max(grantedEnd, pending.start);
		grantedEnd = Math.max(grantedEnd, reach);
		return Math.min(reach, Math.max(head, target));
	}

	/**
	 * @return whether nothing else stands on the sections of a group
	 */
	private boolean isClear(PathClaims.Group group) {
		for (final Arbiter.Claim<UUID> claim : group.claims) {
			if (claim.kind == transitsim.network.Resource.Kind.TRACK && reservations.get().isHeldByOther(holder, claim.resource)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * @return whether the train may go on from its head: something ahead of it was granted
	 */
	public boolean isGrantedBeyond(double head) {
		return grantedEnd > head + 1E-6;
	}

	/**
	 * @return whether the train is waiting for a group it asked for
	 */
	public boolean isWaiting() {
		return pending != null;
	}

	/**
	 * Gives up everything, as when the train is back in its siding or taken away.
	 */
	public void release() {
		reservations.get().release(holder);
		held = new ArrayList<>();
		grantedEnd = Double.NaN;
		pending = null;
		asked.clear();
		pathKey = null;
	}

	/**
	 * @return how far the authority reaches, with groups granted at the end of the last tick it hasn't taken yet, for a
	 * record; NaN for none
	 */
	public double getRecordEnd() {
		if (Double.isNaN(grantedEnd)) {
			return Double.NaN;
		}
		final int granted = reservations.get().peekGrantedGroups(holder);
		return granted > 0 && granted <= asked.size() ? Math.max(grantedEnd, asked.get(granted - 1).end) : grantedEnd;
	}

	/**
	 * Carries on from a record: the next update holds what reached this far, on the path the train has then.
	 */
	public void restore(double recordEnd) {
		restoredEnd = recordEnd;
		grantedEnd = Double.NaN;
	}

	public List<Arbiter.Claim<UUID>> getHeld() {
		return held;
	}
}
