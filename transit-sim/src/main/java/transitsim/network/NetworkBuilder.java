package transitsim.network;

import java.util.*;

/**
 * Compresses a {@link RailGraph} into a {@link Network} and works out the resources on it (contracts document 4.1).
 */
public final class NetworkBuilder {

	private NetworkBuilder() {
	}

	/**
	 * @param regions    the regions of the pathfinding boundaries, or null for none
	 * @param savedRails the platform or siding on each rail, keyed by {@link RailKeys#of}
	 * @param version    the order the network is published in
	 */
	public static <R> Network build(RailGraph<R> graph, PathRegions regions, Boundaries boundaries, Map<UUID, Long> savedRails, RailAttributes<R> attributes, long version) {
		// Each rail once, with both directions
		final Map<UUID, List<RailGraph.Connection<R>>> edges = new TreeMap<>();
		final Map<Long, List<UUID>> nodeEdges = new TreeMap<>();
		for (final RailGraph.Connection<R> connection : graph.connections) {
			final UUID key = RailKeys.of(connection.fromPos, connection.toPos);
			final List<RailGraph.Connection<R>> pair = edges.computeIfAbsent(key, k -> {
				nodeEdges.computeIfAbsent(connection.fromPos, pos -> new ArrayList<>()).add(k);
				if (connection.toPos != connection.fromPos) {
					nodeEdges.computeIfAbsent(connection.toPos, pos -> new ArrayList<>()).add(k);
				}
				return new ArrayList<>(Arrays.asList(null, null));
			});
			pair.set(connection.fromPos < connection.toPos ? 0 : 1, connection);
		}

		final Set<Long> keptNodes = new TreeSet<>();
		nodeEdges.forEach((pos, incident) -> {
			if (incident.size() != 2 || boundaries.nodes.contains(pos)) {
				keptNodes.add(pos);
			}
		});
		edges.forEach((key, pair) -> {
			if (isSpecial(pair, key, savedRails, attributes)) {
				keptNodes.add(key.getMostSignificantBits());
				keptNodes.add(key.getLeastSignificantBits());
			}
		});

		final Map<Long, Network.Section> sections = new TreeMap<>();
		final Map<UUID, long[]> railSections = new HashMap<>();
		final Set<UUID> used = new HashSet<>();
		final List<Long> starts = new ArrayList<>(keptNodes);
		// Rings with no kept node at all start from their lowest node
		starts.addAll(nodeEdges.keySet());
		for (final long start : starts) {
			if (!keptNodes.contains(start) && nodeEdges.get(start).stream().allMatch(used::contains)) {
				continue;
			}
			for (final UUID firstEdge : nodeEdges.get(start)) {
				if (used.contains(firstEdge)) {
					continue;
				}
				final List<Long> nodes = new ArrayList<>();
				final List<UUID> sectionEdges = new ArrayList<>();
				nodes.add(start);
				long current = start;
				UUID edge = firstEdge;
				while (edge != null && used.add(edge)) {
					sectionEdges.add(edge);
					final long next = otherEnd(edge, current);
					nodes.add(next);
					current = next;
					if (keptNodes.contains(next) || isSpecial(edges.get(edge), edge, savedRails, attributes)) {
						break;
					}
					UUID following = null;
					for (final UUID candidate : nodeEdges.get(next)) {
						if (!used.contains(candidate) && !isSpecial(edges.get(candidate), candidate, savedRails, attributes)) {
							following = candidate;
						}
					}
					edge = following;
				}
				if (sectionEdges.isEmpty()) {
					continue;
				}
				// The same way round every time, so ids don't change while the network doesn't
				if (nodes.get(0) > nodes.get(nodes.size() - 1) || nodes.get(0).equals(nodes.get(nodes.size() - 1)) && nodes.size() > 2 && nodes.get(1) > nodes.get(nodes.size() - 2)) {
					Collections.reverse(nodes);
					Collections.reverse(sectionEdges);
				}
				final Network.Section section = buildSection(nodes, sectionEdges, edges, regions, savedRails, attributes);
				sections.put(section.id, section);
				for (int i = 0; i < sectionEdges.size(); i++) {
					railSections.put(sectionEdges.get(i), new long[]{section.id, i, nodes.get(i) < nodes.get(i + 1) ? 1 : 0});
				}
			}
		}
		return new Network(version, sections, railSections, buildResources(sections, railSections, nodeEdges));
	}

	private static <R> boolean isSpecial(List<RailGraph.Connection<R>> pair, UUID key, Map<UUID, Long> savedRails, RailAttributes<R> attributes) {
		return savedRails.containsKey(key) || attributes.getType(getRail(pair)) != SectionType.NORMAL;
	}

	private static <R> R getRail(List<RailGraph.Connection<R>> pair) {
		return pair.get(0) != null ? pair.get(0).rail : pair.get(1).rail;
	}

	private static long otherEnd(UUID edge, long node) {
		return edge.getMostSignificantBits() == node ? edge.getLeastSignificantBits() : edge.getMostSignificantBits();
	}

	private static <R> Network.Section buildSection(List<Long> nodes, List<UUID> sectionEdges, Map<UUID, List<RailGraph.Connection<R>>> edges, PathRegions regions, Map<UUID, Long> savedRails, RailAttributes<R> attributes) {
		final List<double[]> pieces = new ArrayList<>();
		boolean forwards = true;
		boolean backwards = true;
		SectionType type = SectionType.NORMAL;
		for (int i = 0; i < sectionEdges.size(); i++) {
			final List<RailGraph.Connection<R>> pair = edges.get(sectionEdges.get(i));
			final boolean lowerFirst = nodes.get(i) < nodes.get(i + 1);
			final RailGraph.Connection<R> along = pair.get(lowerFirst ? 0 : 1);
			final RailGraph.Connection<R> against = pair.get(lowerFirst ? 1 : 0);
			forwards &= along != null && along.kind != RailGraph.Kind.DISABLED;
			backwards &= against != null && against.kind != RailGraph.Kind.DISABLED;
			final R rail = (along != null ? along : against).rail;
			type = attributes.getType(rail);
			// Rails a train can't speed up on keep the limit of the rail before them, which depends on the way it runs
			final double limit = attributes.canAccelerate(rail) ? attributes.getSpeedLimit(rail) : -1;
			final double length = attributes.getLength(rail);
			if (!pieces.isEmpty() && pieces.get(pieces.size() - 1)[0] == limit) {
				pieces.get(pieces.size() - 1)[1] += length;
			} else {
				pieces.add(new double[]{limit, length});
			}
		}
		final List<RailGraph.Connection<R>> firstPair = edges.get(sectionEdges.get(0));
		final int[] sectionRegions = regions == null ? new int[0] : regions.regionsOf(firstPair.get(0) != null ? firstPair.get(0) : firstPair.get(1));
		final long id = Network.mix(nodes.get(0), nodes.get(1));
		return new Network.Section(id, nodes.stream().mapToLong(Long::longValue).toArray(), pieces, sectionEdges.size() == 1 && sectionEdges.get(0) != null ? savedRails.getOrDefault(sectionEdges.get(0), 0L) : 0, sectionEdges.size() == 1 ? type : SectionType.NORMAL, sectionRegions, forwards, backwards);
	}

	/**
	 * The resources of contracts document 4.1, each with an ID made from its kind and the section or node it is at, so the
	 * same network always has the same resources.
	 */
	private static Map<Long, Resource> buildResources(Map<Long, Network.Section> sections, Map<UUID, long[]> railSections, Map<Long, List<UUID>> nodeEdges) {
		final Map<Long, Resource> resources = new TreeMap<>();
		sections.values().forEach(section -> {
			add(resources, Resource.Kind.TRACK, section.id, new long[]{section.id}, 1);
			if (section.type != SectionType.NORMAL || section.savedRailId != 0) {
				add(resources, Resource.Kind.BERTH, section.id, new long[]{section.id}, 1);
			}
		});

		// Rails branch where a node has three or more, so routes through it can foul each other
		nodeEdges.forEach((node, incident) -> {
			if (incident.size() >= 3) {
				final Set<Long> meeting = new TreeSet<>();
				incident.forEach(edge -> {
					final long[] place = railSections.get(edge);
					if (place != null) {
						meeting.add(place[0]);
					}
				});
				add(resources, Resource.Kind.TURNOUT, node, meeting.stream().mapToLong(Long::longValue).toArray(), 1);
			}
		});

		// Two way sections meeting at a node with no other rail have nowhere to pass, so they make one single track
		final Map<Long, Long> parents = new TreeMap<>();
		sections.values().forEach(section -> {
			if (section.forwards && section.backwards) {
				parents.put(section.id, section.id);
			}
		});
		nodeEdges.forEach((node, incident) -> {
			if (incident.size() == 2) {
				final long[] place1 = railSections.get(incident.get(0));
				final long[] place2 = railSections.get(incident.get(1));
				if (place1 != null && place2 != null && parents.containsKey(place1[0]) && parents.containsKey(place2[0])) {
					union(parents, place1[0], place2[0]);
				}
			}
		});
		final Map<Long, Set<Long>> singleTracks = new TreeMap<>();
		parents.keySet().forEach(sectionId -> singleTracks.computeIfAbsent(find(parents, sectionId), key -> new TreeSet<>()).add(sectionId));
		singleTracks.values().forEach(sectionIds -> add(resources, Resource.Kind.SINGLE_TRACK, sectionIds.iterator().next(), sectionIds.stream().mapToLong(Long::longValue).toArray(), 1));
		return resources;
	}

	private static void add(Map<Long, Resource> resources, Resource.Kind kind, long key, long[] sectionIds, int capacity) {
		final long id = Network.mix(kind.ordinal() + 1, key);
		resources.put(id, new Resource(id, kind, sectionIds, capacity));
	}

	private static long find(Map<Long, Long> parents, long id) {
		long root = id;
		while (parents.get(root) != root) {
			root = parents.get(root);
		}
		long current = id;
		while (current != root) {
			final long next = parents.get(current);
			parents.put(current, root);
			current = next;
		}
		return root;
	}

	private static void union(Map<Long, Long> parents, long id1, long id2) {
		final long root1 = find(parents, id1);
		final long root2 = find(parents, id2);
		if (root1 != root2) {
			// The lower ID is the root, so the same network always makes the same groups
			parents.put(Math.max(root1, root2), Math.min(root1, root2));
		}
	}
}
