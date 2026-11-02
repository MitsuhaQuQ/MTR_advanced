package mtr.advanced;

import mtr.data.Rail;
import mtr.data.RailType;
import mtr.path.RailGraph;

import java.io.*;
import java.util.*;

/**
 * The physical rail network, compressed (design document 5.11, 6.12): nodes are kept where rails branch or end, at
 * pathfinding boundaries, and at both ends of platform, siding and turn back rails, which are sections of their own. The rails
 * between two kept nodes make one section, with its length split by speed limit so running times can be worked out from the
 * section alone. Paths are kept as the sections they run through. The server saves both in its own SQLite tables.
 */
public final class PhysicalGraph {

	public final Map<Long, Section> sections;
	/**
	 * Rail (both directions together) → the section it is in, its place in the section, and whether the rail's lower node
	 * comes first along the section.
	 */
	private final Map<UUID, long[]> railSections;

	private PhysicalGraph(Map<Long, Section> sections, Map<UUID, long[]> railSections) {
		this.sections = sections;
		this.railSections = railSections;
	}

	/**
	 * @param savedRails the platform or siding on each rail, keyed like {@link PathBoundaries#segmentKey}
	 */
	public static PhysicalGraph compute(RailGraph<Rail> graph, PathRegions regions, PathBoundaries boundaries, Map<UUID, Long> savedRails) {
		// Each rail once, with both directions
		final Map<UUID, RailGraph.Connection<Rail>[]> edges = new TreeMap<>();
		final Map<Long, List<UUID>> nodeEdges = new TreeMap<>();
		for (final RailGraph.Connection<Rail> connection : graph.connections) {
			final UUID key = PathBoundaries.segmentKey(connection.fromPos, connection.toPos);
			@SuppressWarnings("unchecked") final RailGraph.Connection<Rail>[] pair = edges.computeIfAbsent(key, k -> {
				nodeEdges.computeIfAbsent(connection.fromPos, pos -> new ArrayList<>()).add(k);
				if (connection.toPos != connection.fromPos) {
					nodeEdges.computeIfAbsent(connection.toPos, pos -> new ArrayList<>()).add(k);
				}
				return new RailGraph.Connection[2];
			});
			pair[connection.fromPos < connection.toPos ? 0 : 1] = connection;
		}

		final Set<Long> keptNodes = new TreeSet<>();
		nodeEdges.forEach((pos, incident) -> {
			if (incident.size() != 2 || boundaries.nodes.contains(pos)) {
				keptNodes.add(pos);
			}
		});
		edges.forEach((key, pair) -> {
			if (isSpecial(pair, key, savedRails)) {
				keptNodes.add(key.getMostSignificantBits());
				keptNodes.add(key.getLeastSignificantBits());
			}
		});

		final Map<Long, Section> sections = new TreeMap<>();
		final Map<UUID, long[]> railSections = new HashMap<>();
		final Set<UUID> used = new HashSet<>();
		final List<Long> starts = new ArrayList<>(keptNodes);
		// Rings with no kept node at all start from their lowest node
		nodeEdges.keySet().forEach(starts::add);
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
					if (keptNodes.contains(next) || isSpecial(edges.get(edge), edge, savedRails)) {
						break;
					}
					UUID following = null;
					for (final UUID candidate : nodeEdges.get(next)) {
						if (!used.contains(candidate) && !isSpecial(edges.get(candidate), candidate, savedRails)) {
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
				final Section section = buildSection(nodes, sectionEdges, edges, regions, savedRails);
				sections.put(section.id, section);
				for (int i = 0; i < sectionEdges.size(); i++) {
					railSections.put(sectionEdges.get(i), new long[]{section.id, i, nodes.get(i) < nodes.get(i + 1) ? 1 : 0});
				}
			}
		}
		return new PhysicalGraph(sections, railSections);
	}

	private static boolean isSpecial(RailGraph.Connection<Rail>[] pair, UUID key, Map<UUID, Long> savedRails) {
		final Rail rail = pair[0] != null ? pair[0].rail : pair[1].rail;
		return savedRails.containsKey(key) || rail.railType == RailType.PLATFORM || rail.railType == RailType.SIDING || rail.railType == RailType.TURN_BACK;
	}

	private static long otherEnd(UUID edge, long node) {
		return edge.getMostSignificantBits() == node ? edge.getLeastSignificantBits() : edge.getMostSignificantBits();
	}

	private static Section buildSection(List<Long> nodes, List<UUID> sectionEdges, Map<UUID, RailGraph.Connection<Rail>[]> edges, PathRegions regions, Map<UUID, Long> savedRails) {
		final List<double[]> pieces = new ArrayList<>();
		boolean forwards = true;
		boolean backwards = true;
		RailType type = RailType.IRON;
		for (int i = 0; i < sectionEdges.size(); i++) {
			final RailGraph.Connection<Rail>[] pair = edges.get(sectionEdges.get(i));
			final boolean lowerFirst = nodes.get(i) < nodes.get(i + 1);
			final RailGraph.Connection<Rail> along = pair[lowerFirst ? 0 : 1];
			final RailGraph.Connection<Rail> against = pair[lowerFirst ? 1 : 0];
			forwards &= along != null && along.kind != RailGraph.Kind.DISABLED;
			backwards &= against != null && against.kind != RailGraph.Kind.DISABLED;
			final Rail rail = (along != null ? along : against).rail;
			type = rail.railType;
			// Rails a train can't speed up on keep the limit of the rail before them, which depends on the way it runs
			final double limit = rail.railType.canAccelerate ? rail.railType.maxBlocksPerTick : -1;
			final double length = rail.getLength();
			if (!pieces.isEmpty() && pieces.get(pieces.size() - 1)[0] == limit) {
				pieces.get(pieces.size() - 1)[1] += length;
			} else {
				pieces.add(new double[]{limit, length});
			}
		}
		final RailGraph.Connection<Rail>[] firstPair = edges.get(sectionEdges.get(0));
		final int[] sectionRegions = regions == null ? new int[0] : regions.regionsOf(firstPair[0] != null ? firstPair[0] : firstPair[1]);
		final long id = mix(nodes.get(0), nodes.get(1));
		return new Section(id, nodes.stream().mapToLong(Long::longValue).toArray(), pieces, sectionEdges.size() == 1 && sectionEdges.get(0) != null ? savedRails.getOrDefault(sectionEdges.get(0), 0L) : 0, sectionEdges.size() == 1 ? type : RailType.IRON, sectionRegions, forwards, backwards);
	}

	/**
	 * A path as the sections it runs through: each step is {section, 1 if along the section's order else 0}, with the
	 * stops and reversals marked where they are.
	 *
	 * @param moves each rail of the path in order as {from node, to node, stop time, platform or 0}
	 */
	public SectionPath toSectionPath(List<long[]> moves) {
		final SectionPath sectionPath = new SectionPath();
		for (final long[] move : moves) {
			final long[] place = railSections.get(PathBoundaries.segmentKey(move[0], move[1]));
			if (place == null) {
				sectionPath.missingRails++;
				continue;
			}
			final boolean lowerFirst = move[0] < move[1];
			final long along = lowerFirst == (place[2] == 1) ? 1 : 0;
			final long[] last = sectionPath.steps.isEmpty() ? null : sectionPath.steps.get(sectionPath.steps.size() - 1);
			if (last == null || last[0] != place[0] || last[1] != along) {
				if (last != null && last[0] == place[0]) {
					sectionPath.reversals.add(sectionPath.steps.size());
				}
				sectionPath.steps.add(new long[]{place[0], along});
			}
			if (move[2] > 0 || move[3] != 0) {
				sectionPath.stops.add(new long[]{sectionPath.steps.size() - 1, move[3], move[2]});
			}
		}
		return sectionPath;
	}

	/**
	 * @return a hash of the whole network, which changes when any section does
	 */
	public long getFingerprint() {
		long hash = 17;
		for (final Section section : sections.values()) {
			hash = hash * 31 + section.id;
			hash = hash * 31 + Arrays.hashCode(section.nodes);
		}
		return hash;
	}

	private static long mix(long value1, long value2) {
		long hash = value1 * 0x9E3779B97F4A7C15L + value2;
		hash = (hash ^ hash >>> 31) * 0xBF58476D1CE4E5B9L;
		return hash ^ hash >>> 29;
	}

	public static final class Section {

		public final long id;
		public final long[] nodes;
		/**
		 * Each {speed limit in blocks per tick, length}, in order along the section. A limit of -1 keeps the limit of the rail
		 * before it, as on platforms and sidings.
		 */
		public final List<double[]> limits;
		public final long savedRailId;
		public final RailType type;
		public final int[] regions;
		public final boolean forwards;
		public final boolean backwards;

		private Section(long id, long[] nodes, List<double[]> limits, long savedRailId, RailType type, int[] regions, boolean forwards, boolean backwards) {
			this.id = id;
			this.nodes = nodes;
			this.limits = limits;
			this.savedRailId = savedRailId;
			this.type = type;
			this.regions = regions;
			this.forwards = forwards;
			this.backwards = backwards;
		}

		public double getLength() {
			return limits.stream().mapToDouble(limit -> limit[1]).sum();
		}

		public byte[] toBytes() {
			try {
				final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				final DataOutputStream output = new DataOutputStream(bytes);
				output.writeInt(nodes.length);
				for (final long node : nodes) {
					output.writeLong(node);
				}
				output.writeInt(limits.size());
				for (final double[] limit : limits) {
					output.writeDouble(limit[0]);
					output.writeDouble(limit[1]);
				}
				output.writeLong(savedRailId);
				output.writeUTF(type.toString());
				output.writeInt(regions.length);
				for (final int region : regions) {
					output.writeInt(region);
				}
				output.writeBoolean(forwards);
				output.writeBoolean(backwards);
				return bytes.toByteArray();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		public static Section fromBytes(long id, byte[] data) throws IOException {
			final DataInputStream input = new DataInputStream(new ByteArrayInputStream(data));
			final long[] nodes = new long[limit(input.readInt())];
			for (int i = 0; i < nodes.length; i++) {
				nodes[i] = input.readLong();
			}
			final List<double[]> limits = new ArrayList<>();
			final int limitCount = limit(input.readInt());
			for (int i = 0; i < limitCount; i++) {
				limits.add(new double[]{input.readDouble(), input.readDouble()});
			}
			final long savedRailId = input.readLong();
			RailType type;
			try {
				type = RailType.valueOf(input.readUTF());
			} catch (Exception e) {
				type = RailType.IRON;
			}
			final int[] regions = new int[limit(input.readInt())];
			for (int i = 0; i < regions.length; i++) {
				regions[i] = input.readInt();
			}
			return new Section(id, nodes, limits, savedRailId, type, regions, input.readBoolean(), input.readBoolean());
		}
	}

	public static final class SectionPath {

		public final List<long[]> steps = new ArrayList<>();
		/**
		 * Each {step, platform or 0, stop time}.
		 */
		public final List<long[]> stops = new ArrayList<>();
		/**
		 * The steps where the train has reversed.
		 */
		public final List<Integer> reversals = new ArrayList<>();
		/**
		 * Rails of the path the graph doesn't have, which happens when the network changed since.
		 */
		public int missingRails;

		public byte[] toBytes() {
			try {
				final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				final DataOutputStream output = new DataOutputStream(bytes);
				output.writeInt(steps.size());
				for (final long[] step : steps) {
					output.writeLong(step[0]);
					output.writeBoolean(step[1] == 1);
				}
				output.writeInt(stops.size());
				for (final long[] stop : stops) {
					output.writeInt((int) stop[0]);
					output.writeLong(stop[1]);
					output.writeInt((int) stop[2]);
				}
				output.writeInt(reversals.size());
				for (final int reversal : reversals) {
					output.writeInt(reversal);
				}
				output.writeInt(missingRails);
				return bytes.toByteArray();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		public static SectionPath fromBytes(byte[] data) throws IOException {
			final DataInputStream input = new DataInputStream(new ByteArrayInputStream(data));
			final SectionPath sectionPath = new SectionPath();
			final int stepCount = limit(input.readInt());
			for (int i = 0; i < stepCount; i++) {
				sectionPath.steps.add(new long[]{input.readLong(), input.readBoolean() ? 1 : 0});
			}
			final int stopCount = limit(input.readInt());
			for (int i = 0; i < stopCount; i++) {
				sectionPath.stops.add(new long[]{input.readInt(), input.readLong(), input.readInt()});
			}
			final int reversalCount = limit(input.readInt());
			for (int i = 0; i < reversalCount; i++) {
				sectionPath.reversals.add(input.readInt());
			}
			sectionPath.missingRails = input.readInt();
			return sectionPath;
		}
	}

	private static int limit(int count) throws IOException {
		if (count < 0 || count > 1 << 22) {
			throw new IOException("Bad count " + count);
		}
		return count;
	}
}
