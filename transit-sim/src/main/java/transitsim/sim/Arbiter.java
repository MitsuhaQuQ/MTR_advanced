package transitsim.sim;

import transitsim.network.Resource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Decides which train may use which resources (contracts document section 4, S4b). Block sections, single track with a
 * direction, turnouts and berths are all resources here, granted by the same rules, so none of them is decided on its
 * own.
 * <p>
 * Three things are kept apart (4.2): what trains actually stand on, given by the host from where each train's head and
 * tail are; what they have been granted, which only the arbiter gives; and what they are predicted to use, which is only
 * worked out in forks of a simulation and never counts for a grant.
 * <p>
 * Requests of one event boundary are dealt with together once it is over, in a fixed order, so the result doesn't depend
 * on the order they came in: requests that have waited {@link #AGING_MILLIS} or longer first, longest waiting first, then
 * the rest by planned departure, then by holder. A request gets all its resources or none. While a request that has
 * waited that long can't be granted, what it asks for is kept from holders that don't hold any of it yet, so the ones
 * already there can clear out and a stream of others can't keep it waiting for ever.
 * <p>
 * Nothing is let go because a train stopped or a renewal lapsed: the holder says what it still holds, only giving up
 * resources its tail has cleared, or is removed. Holders that renew every tick ({@link #hold}) and say nothing in a tick
 * are taken as removed when it ends, which is how the game's server trains use it.
 * <p>
 * When holders wait for each other in a circle, that is a deadlock (4.5): it is reported once, no new grants are made
 * into the resources of the circle, and nothing is let go by itself. Only the host or a player can clear it.
 */
public final class Arbiter<K extends Comparable<K>> {

	/**
	 * How long a request may wait before it goes ahead of ones due to leave earlier.
	 */
	public static final long AGING_MILLIS = 60000;

	private final Map<Long, List<Claim<K>>> holdings = new HashMap<>();
	private final Map<K, Map<Long, Claim<K>>> holders = new HashMap<>();
	private final Map<Long, Set<K>> occupied = new HashMap<>();
	private final Map<K, Set<Long>> occupants = new HashMap<>();
	private final Map<Long, Set<K>> predicted = new HashMap<>();
	private final Set<Long> heldThisTick = new HashSet<>();
	private final Map<Long, Request<K>> requests = new HashMap<>();
	private final Map<Long, Long> firstRequestMillis = new HashMap<>();
	private final Set<Long> grants = new HashSet<>();
	/**
	 * Resources of a deadlock, with the holders in it, which no other holder is granted until it clears.
	 */
	private final Map<K, Set<Long>> frozen = new HashMap<>();
	private final Set<Set<Long>> knownDeadlocks = new HashSet<>();
	private final List<Set<Long>> newDeadlocks = new ArrayList<>();

	// Granted

	/**
	 * Sets what a holder is granted, replacing what it had: a holder renewing every tick gives what it still needs, from
	 * its tail onwards.
	 */
	public void hold(long holder, List<Claim<K>> claims) {
		removeHoldings(holder);
		heldThisTick.add(holder);
		if (!claims.isEmpty()) {
			holdings.put(holder, new ArrayList<>(claims));
			claims.forEach(claim -> holders.computeIfAbsent(claim.resource, key -> new HashMap<>()).put(holder, claim));
		}
	}

	/**
	 * Takes what a holder was granted, as when the train is back in its depot. A request it made stays, as a train in its
	 * siding asks for its way out before it is on the line.
	 */
	public void release(long holder) {
		removeHoldings(holder);
		heldThisTick.remove(holder);
	}

	/**
	 * Takes everything of a holder, grants, requests and what it stands on, as when the host removes the train.
	 */
	public void remove(long holder) {
		release(holder);
		setOccupied(holder, Collections.emptySet());
		predicted.remove(holder);
		requests.remove(holder);
		firstRequestMillis.remove(holder);
		grants.remove(holder);
	}

	/**
	 * Takes back what a holder was granted but hasn't entered, as when a train changes its path at a stop (4.4). It is only
	 * safe while the train stands still with its head short of where the grant starts.
	 *
	 * @param keep what the holder keeps, which it has entered
	 * @return false if it isn't safe, when nothing is taken back
	 */
	public boolean cancel(long holder, boolean stopped, boolean headBeforeGrant, List<Claim<K>> keep) {
		if (!stopped || !headBeforeGrant) {
			return false;
		}
		final boolean renews = heldThisTick.contains(holder);
		hold(holder, keep);
		if (!renews) {
			heldThisTick.remove(holder);
		}
		return true;
	}

	/**
	 * @return everybody granted something, in order
	 */
	public Set<Long> getHolders() {
		return new TreeSet<>(holdings.keySet());
	}

	/**
	 * @return what the holder is granted
	 */
	public List<Claim<K>> getHoldings(long holder) {
		final List<Claim<K>> claims = holdings.get(holder);
		return claims == null ? Collections.emptyList() : Collections.unmodifiableList(claims);
	}

	/**
	 * @return false if these resources can't be granted to the holder now, by what others are granted or stand on
	 */
	public boolean canHold(long holder, List<Claim<K>> claims) {
		for (final Claim<K> claim : claims) {
			if (!blockers(holder, claim).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	// Actually standing on

	/**
	 * Sets what a train, or something else the host knows of, stands on now, from its tail to its head.
	 */
	public void setOccupied(long occupant, Collection<K> resources) {
		final Set<K> old = occupied.remove(occupant);
		if (old != null) {
			old.forEach(resource -> {
				final Set<Long> resourceOccupants = occupants.get(resource);
				if (resourceOccupants != null) {
					resourceOccupants.remove(occupant);
					if (resourceOccupants.isEmpty()) {
						occupants.remove(resource);
					}
				}
			});
		}
		if (!resources.isEmpty()) {
			occupied.put(occupant, new TreeSet<>(resources));
			resources.forEach(resource -> occupants.computeIfAbsent(resource, key -> new TreeSet<>()).add(occupant));
		}
	}

	public boolean isOccupied(K resource) {
		return occupants.containsKey(resource);
	}

	// Predicted

	/**
	 * Sets what a train is predicted to use, for conflict checks of a fork. It is never taken as standing on it or as a
	 * grant (4.2, rule 3).
	 */
	public void setPredicted(long holder, Collection<K> resources) {
		if (resources.isEmpty()) {
			predicted.remove(holder);
		} else {
			predicted.put(holder, new TreeSet<>(resources));
		}
	}

	public Set<K> getPredicted(long holder) {
		final Set<K> resources = predicted.get(holder);
		return resources == null ? Collections.emptySet() : Collections.unmodifiableSet(resources);
	}

	// Requests

	/**
	 * Asks for resources, which are granted or not once the event boundary is over. A holder still waiting asks again at
	 * the next one; one that stops asking is dropped.
	 *
	 * @param plannedMillis when the train is planned to leave
	 */
	public void request(long holder, List<Claim<K>> claims, long plannedMillis) {
		requests.put(holder, new Request<>(holder, new ArrayList<>(claims), plannedMillis));
	}

	/**
	 * @return true once, after the holder's request was granted, when it holds what it asked for
	 */
	public boolean takeGrant(long holder) {
		return grants.remove(holder);
	}

	/**
	 * Ends an event boundary: holders renewing every tick that didn't this time give up what they had, then the requests
	 * are dealt with, and deadlocks looked for.
	 */
	public void resolve(long currentMillis) {
		new ArrayList<>(holdings.keySet()).forEach(holder -> {
			if (!heldThisTick.contains(holder)) {
				removeHoldings(holder);
			}
		});
		grants.clear();
		firstRequestMillis.keySet().retainAll(requests.keySet());
		// A deadlock one of whose holders is gone, removed by the host, is over
		knownDeadlocks.removeIf(deadlock -> deadlock.stream().anyMatch(holder -> !requests.containsKey(holder)));
		frozen.values().removeIf(members -> !knownDeadlocks.contains(members));

		final List<Request<K>> waiting = new ArrayList<>(requests.values());
		waiting.forEach(request -> firstRequestMillis.putIfAbsent(request.holder, currentMillis));
		waiting.sort((request1, request2) -> {
			final long first1 = firstRequestMillis.get(request1.holder);
			final long first2 = firstRequestMillis.get(request2.holder);
			final boolean aged1 = currentMillis - first1 >= AGING_MILLIS;
			final boolean aged2 = currentMillis - first2 >= AGING_MILLIS;
			if (aged1 != aged2) {
				return aged1 ? -1 : 1;
			}
			final int byTime = aged1 ? Long.compare(first1, first2) : Long.compare(request1.plannedMillis, request2.plannedMillis);
			return byTime != 0 ? byTime : Long.compare(request1.holder, request2.holder);
		});

		// What a request that has waited too long is still kept from. No holder new to it is let onto it against the request,
		// so the ones already there can clear out and a steady stream can't starve the request.
		final Map<K, Claim<K>> waitedFor = new HashMap<>();
		final List<Request<K>> notGranted = new ArrayList<>();
		for (final Request<K> request : waiting) {
			if (canHold(request.holder, request.claims) && !isFrozenFor(request) && (holdsAny(request.holder, waitedFor) || !isAgainst(request.claims, waitedFor))) {
				hold(request.holder, request.claims);
				grants.add(request.holder);
				firstRequestMillis.remove(request.holder);
			} else {
				notGranted.add(request);
				if (currentMillis - firstRequestMillis.get(request.holder) >= AGING_MILLIS) {
					request.claims.forEach(claim -> waitedFor.putIfAbsent(claim.resource, claim));
				}
			}
		}
		findDeadlocks(notGranted);
		requests.clear();
		// Every holder renewing by tick says what it holds again next tick, including what was just granted
		heldThisTick.clear();
	}

	/**
	 * @return the holders of each deadlock found since this was last called, each once, in a fixed order
	 */
	public List<Set<Long>> takeDeadlocks() {
		final List<Set<Long>> deadlocks = new ArrayList<>(newDeadlocks);
		newDeadlocks.clear();
		return deadlocks;
	}

	public boolean isFrozen(K resource) {
		return frozen.containsKey(resource);
	}

	/**
	 * Holders waiting for what others are granted or stand on make a graph, and a circle in it is a deadlock. Its resources
	 * are kept from new holders while it lasts; nothing is let go.
	 */
	private void findDeadlocks(List<Request<K>> notGranted) {
		final Map<Long, Request<K>> waitingRequests = new TreeMap<>();
		notGranted.forEach(request -> waitingRequests.put(request.holder, request));
		final Map<Long, Set<Long>> waitsFor = new TreeMap<>();
		waitingRequests.values().forEach(request -> {
			final Set<Long> others = new TreeSet<>();
			request.claims.forEach(claim -> others.addAll(blockers(request.holder, claim)));
			others.retainAll(waitingRequests.keySet());
			if (!others.isEmpty()) {
				waitsFor.put(request.holder, others);
			}
		});

		final Set<Set<Long>> deadlocks = new HashSet<>();
		final Set<Long> done = new HashSet<>();
		for (final long start : waitsFor.keySet()) {
			findCircle(start, waitsFor, new ArrayList<>(), new HashSet<>(), done, deadlocks);
		}

		frozen.clear();
		final List<Set<Long>> sorted = new ArrayList<>(deadlocks);
		sorted.sort((set1, set2) -> Long.compare(Collections.min(set1), Collections.min(set2)));
		for (final Set<Long> deadlock : sorted) {
			deadlock.forEach(holder -> {
				waitingRequests.get(holder).claims.forEach(claim -> frozen.putIfAbsent(claim.resource, deadlock));
				getHoldings(holder).forEach(claim -> frozen.putIfAbsent(claim.resource, deadlock));
			});
			if (!knownDeadlocks.contains(deadlock)) {
				newDeadlocks.add(deadlock);
			}
		}
		knownDeadlocks.clear();
		knownDeadlocks.addAll(deadlocks);
	}

	private static void findCircle(long holder, Map<Long, Set<Long>> waitsFor, List<Long> trail, Set<Long> onTrail, Set<Long> done, Set<Set<Long>> deadlocks) {
		if (onTrail.contains(holder)) {
			deadlocks.add(new TreeSet<>(trail.subList(trail.indexOf(holder), trail.size())));
			return;
		}
		if (!done.add(holder)) {
			return;
		}
		trail.add(holder);
		onTrail.add(holder);
		for (final long other : waitsFor.getOrDefault(holder, Collections.emptySet())) {
			findCircle(other, waitsFor, trail, onTrail, done, deadlocks);
		}
		trail.remove(trail.size() - 1);
		onTrail.remove(holder);
	}

	/**
	 * @return the others that keep this claim from the holder now
	 */
	private Set<Long> blockers(long holder, Claim<K> claim) {
		final Set<Long> blockers = new TreeSet<>();
		final Map<Long, Claim<K>> resourceHolders = holders.get(claim.resource);
		int others = 0;
		if (resourceHolders != null) {
			for (final Map.Entry<Long, Claim<K>> entry : resourceHolders.entrySet()) {
				if (entry.getKey() != holder) {
					others++;
					if (claim.kind != Resource.Kind.BERTH && conflicts(claim, entry.getValue())) {
						blockers.add(entry.getKey());
					}
				}
			}
		}
		if (claim.kind == Resource.Kind.BERTH && others >= claim.capacity) {
			resourceHolders.keySet().stream().filter(other -> other != holder).forEach(blockers::add);
		}
		// Something standing on a track or turnout without a grant for it, such as a train driven by hand, keeps it too
		if (claim.kind == Resource.Kind.TRACK || claim.kind == Resource.Kind.TURNOUT) {
			final Set<Long> resourceOccupants = occupants.get(claim.resource);
			if (resourceOccupants != null) {
				resourceOccupants.stream().filter(occupant -> occupant != holder && (resourceHolders == null || !resourceHolders.containsKey(occupant))).forEach(blockers::add);
			}
		}
		return blockers;
	}

	/**
	 * Two claims of the same resource that can't be granted together.
	 */
	private static <K> boolean conflicts(Claim<K> claim, Claim<K> other) {
		switch (claim.kind) {
			case SINGLE_TRACK:
				return claim.way != other.way;
			case TURNOUT:
				// Trains on the same route through it follow each other, which the block sections keep apart
				return claim.way != other.way;
			case BERTH:
				return false;
			default:
				return true;
		}
	}

	private boolean isFrozenFor(Request<K> request) {
		for (final Claim<K> claim : request.claims) {
			final Set<Long> members = frozen.get(claim.resource);
			// The holders of the deadlock themselves may still go on, should one of them be able to
			if (members != null && !members.contains(request.holder) && !holdsResource(request.holder, claim.resource)) {
				return true;
			}
		}
		return false;
	}

	private boolean holdsResource(long holder, K resource) {
		final Map<Long, Claim<K>> resourceHolders = holders.get(resource);
		return resourceHolders != null && resourceHolders.containsKey(holder);
	}

	/**
	 * @return true if the holder already holds some of these resources, so it is among the ones to let clear out
	 */
	private boolean holdsAny(long holder, Map<K, Claim<K>> waitedFor) {
		final List<Claim<K>> claims = holdings.get(holder);
		if (claims != null) {
			for (final Claim<K> claim : claims) {
				if (waitedFor.containsKey(claim.resource)) {
					return true;
				}
			}
		}
		return false;
	}

	private static <K> boolean isAgainst(List<Claim<K>> claims, Map<K, Claim<K>> waitedFor) {
		for (final Claim<K> claim : claims) {
			final Claim<K> waiting = waitedFor.get(claim.resource);
			if (waiting != null && (claim.kind == Resource.Kind.BERTH || conflicts(claim, waiting))) {
				return true;
			}
		}
		return false;
	}

	private void removeHoldings(long holder) {
		final List<Claim<K>> claims = holdings.remove(holder);
		if (claims != null) {
			claims.forEach(claim -> {
				final Map<Long, Claim<K>> resourceHolders = holders.get(claim.resource);
				if (resourceHolders != null) {
					resourceHolders.remove(holder);
					if (resourceHolders.isEmpty()) {
						holders.remove(claim.resource);
					}
				}
			});
		}
	}

	/**
	 * A resource asked for or granted, with how it is used.
	 */
	public static final class Claim<K> {

		public final K resource;
		public final Resource.Kind kind;
		/**
		 * The direction along a single track, or the route through a turnout. Claims of the same resource with the same way
		 * can be granted together.
		 */
		public final int way;
		/**
		 * For a berth, how many trains it takes.
		 */
		public final int capacity;

		private Claim(K resource, Resource.Kind kind, int way, int capacity) {
			this.resource = resource;
			this.kind = kind;
			this.way = way;
			this.capacity = capacity;
		}

		/**
		 * A section one train has at a time.
		 */
		public static <K> Claim<K> track(K resource) {
			return new Claim<>(resource, Resource.Kind.TRACK, 0, 1);
		}

		/**
		 * @param forwards the direction along it, which trains going the same way share
		 */
		public static <K> Claim<K> singleTrack(K resource, boolean forwards) {
			return new Claim<>(resource, Resource.Kind.SINGLE_TRACK, forwards ? 1 : -1, 1);
		}

		/**
		 * @param route which way through it, which trains going the same way share
		 */
		public static <K> Claim<K> turnout(K resource, int route) {
			return new Claim<>(resource, Resource.Kind.TURNOUT, route, 1);
		}

		public static <K> Claim<K> berth(K resource, int capacity) {
			return new Claim<>(resource, Resource.Kind.BERTH, 0, Math.max(1, capacity));
		}
	}

	private static final class Request<K> {

		private final long holder;
		private final List<Claim<K>> claims;
		private final long plannedMillis;

		private Request(long holder, List<Claim<K>> claims, long plannedMillis) {
			this.holder = holder;
			this.claims = claims;
			this.plannedMillis = plannedMillis;
		}
	}
}
