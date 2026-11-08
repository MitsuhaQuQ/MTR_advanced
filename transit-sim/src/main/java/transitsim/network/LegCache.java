package transitsim.network;

import java.util.*;

/**
 * The legs found, kept until what they depend on changes (stages document round 2): each leg is indexed by every section
 * its search touched and every node it went on from. A section removed or changed, or one whose owners changed, drops the
 * legs that touched it; a section added at a node a search went on from (new track joined where a search passed) drops
 * those legs too. So a new shortcut finds the legs it could change again, and nothing else.
 * <p>
 * Legs are found on other threads from a snapshot of a given version. One found on a version older than a change it
 * depends on is never taken in, so what is kept is always right for the network as it is now.
 * <p>
 * Thread safe.
 */
public final class LegCache {

	private final Map<Long, LegRouter.Leg> legs = new HashMap<>();
	private final Map<Long, Set<Long>> bySection = new HashMap<>();
	private final Map<Long, Set<Long>> byNode = new HashMap<>();
	/**
	 * The changes since the oldest version a leg can still be taken in from: version, sections, nodes.
	 */
	private final ArrayDeque<Object[]> changes = new ArrayDeque<>();
	private long version;
	/**
	 * Counts each leg taken in or dropped, so a host can tell nothing changed since it last saved the legs.
	 */
	private long modifications;

	private static final int MAX_CHANGES = 256;

	/**
	 * @return the leg kept for a request, or null
	 */
	public synchronized LegRouter.Leg get(LegRouter.Request request) {
		return legs.get(request.key);
	}

	/**
	 * @return whether every leg is still the one kept, so what was worked out from them still holds
	 */
	public synchronized boolean holds(Collection<LegRouter.Leg> used) {
		for (final LegRouter.Leg leg : used) {
			if (legs.get(leg.request.key) != leg) {
				return false;
			}
		}
		return true;
	}

	/**
	 * @return the version legs are found on now
	 */
	public synchronized long getVersion() {
		return version;
	}

	/**
	 * @return how many times a leg was taken in or dropped so far: the same as before if the legs kept are the same
	 */
	public synchronized long getModifications() {
		return modifications;
	}

	/**
	 * Takes in a leg found on a version, unless the network changed since where it searched, or the same leg found on
	 * the same version or a later one is kept already, as when two threads found it at once.
	 *
	 * @return the leg kept for its request: this one, the one kept already, or null if it wasn't taken in
	 */
	public synchronized LegRouter.Leg keep(LegRouter.Leg leg) {
		final LegRouter.Leg existing = legs.get(leg.request.key);
		if (existing != null && existing.version >= leg.version) {
			return existing;
		}
		return put(leg) ? leg : null;
	}

	/**
	 * Takes in a leg found on a version, unless the network changed since where it searched.
	 *
	 * @return whether it was taken in
	 */
	public synchronized boolean put(LegRouter.Leg leg) {
		if (leg.version < version) {
			if (changes.isEmpty() || (long) changes.peekFirst()[0] > leg.version + 1) {
				// Older than the changes kept: it can't be checked
				return false;
			}
			final Set<Long> sections = toSet(leg.domainSections);
			final Set<Long> nodes = toSet(leg.domainNodes);
			for (final Object[] change : changes) {
				// A change of owners only matters to a leg kept to a line's track
				final boolean applies = !(boolean) change[3] || leg.request.line != null;
				if ((long) change[0] > leg.version && applies && (intersects(sections, (Set<?>) change[1]) || intersects(nodes, (Set<?>) change[2]))) {
					return false;
				}
			}
		} else if (leg.version > version) {
			return false;
		}
		remove(leg.request.key);
		legs.put(leg.request.key, leg);
		modifications++;
		for (final long section : leg.domainSections) {
			bySection.computeIfAbsent(section, key -> new HashSet<>()).add(leg.request.key);
		}
		for (final long node : leg.domainNodes) {
			byNode.computeIfAbsent(node, key -> new HashSet<>()).add(leg.request.key);
		}
		return true;
	}

	/**
	 * A new version of the network: drops the legs the changes touch.
	 *
	 * @param changedSections sections removed or added, or whose owners changed
	 * @param joinedNodes     the end nodes of sections added
	 * @return the keys of the legs dropped
	 */
	public synchronized Set<Long> change(Collection<Long> changedSections, Collection<Long> joinedNodes) {
		return change(changedSections, joinedNodes, false);
	}

	/**
	 * A new version where only the owners of some sections changed: drops the legs kept to a line's track that touched
	 * them, not those of connecting lines, which run anywhere.
	 *
	 * @return the keys of the legs dropped
	 */
	public synchronized Set<Long> changeOwners(Collection<Long> changedSections) {
		return change(changedSections, Collections.emptySet(), true);
	}

	private Set<Long> change(Collection<Long> changedSections, Collection<Long> joinedNodes, boolean ownersOnly) {
		version++;
		final Set<Long> sections = new HashSet<>(changedSections);
		final Set<Long> nodes = new HashSet<>(joinedNodes);
		changes.addLast(new Object[]{version, sections, nodes, ownersOnly});
		while (changes.size() > MAX_CHANGES) {
			changes.removeFirst();
		}
		final Set<Long> dropped = new TreeSet<>();
		sections.forEach(section -> dropped.addAll(bySection.getOrDefault(section, Collections.emptySet())));
		nodes.forEach(node -> dropped.addAll(byNode.getOrDefault(node, Collections.emptySet())));
		if (ownersOnly) {
			dropped.removeIf(key -> legs.get(key).request.line == null);
		}
		dropped.forEach(this::remove);
		return dropped;
	}

	/**
	 * Drops every leg, as when the rules change.
	 */
	public synchronized void clear() {
		legs.clear();
		modifications++;
		bySection.clear();
		byNode.clear();
		version++;
		changes.clear();
	}

	public synchronized int size() {
		return legs.size();
	}

	/**
	 * @return the legs kept, for saving
	 */
	public synchronized List<LegRouter.Leg> getLegs() {
		return new ArrayList<>(legs.values());
	}

	private void remove(long key) {
		final LegRouter.Leg leg = legs.remove(key);
		if (leg == null) {
			return;
		}
		modifications++;
		for (final long section : leg.domainSections) {
			final Set<Long> keys = bySection.get(section);
			if (keys != null) {
				keys.remove(key);
				if (keys.isEmpty()) {
					bySection.remove(section);
				}
			}
		}
		for (final long node : leg.domainNodes) {
			final Set<Long> keys = byNode.get(node);
			if (keys != null) {
				keys.remove(key);
				if (keys.isEmpty()) {
					byNode.remove(node);
				}
			}
		}
	}

	private static Set<Long> toSet(long[] values) {
		final Set<Long> set = new HashSet<>();
		for (final long value : values) {
			set.add(value);
		}
		return set;
	}

	private static boolean intersects(Set<Long> set, Set<?> other) {
		for (final Object value : other) {
			if (set.contains(value)) {
				return true;
			}
		}
		return false;
	}
}
