package transitsim.network;

import java.io.*;
import java.util.*;

/**
 * A snapshot of the physical rail network, compressed (design document 5.11, 6.12; contracts document 1, 4.1 and 5.1):
 * nodes are kept where rails branch or end, at pathfinding boundaries, and at both ends of platform, siding and turn back
 * rails, which are sections of their own. The rails between two kept nodes make one section, with its length split by
 * speed limit so running times can be worked out from the section alone. Paths are kept as the sections they run through.
 * <p>
 * A snapshot never changes once built. It has the version it was published as, and the resources trains need on it.
 * {@link NetworkBuilder} builds it.
 */
public final class Network {

	/**
	 * The order it was published in, so runs can tell which network they started on.
	 */
	public final long version;
	public final Map<Long, Section> sections;
	public final Map<Long, Resource> resources;
	/**
	 * Rail (both directions together) → the section it is in, its place in the section, and whether the rail's lower node
	 * comes first along the section.
	 */
	private final Map<UUID, long[]> railSections;
	private final Map<Long, List<Resource>> sectionResources;
	private final long fingerprint;

	public static final Network EMPTY = new Network(0, new TreeMap<>(), new HashMap<>(), new TreeMap<>());

	Network(long version, Map<Long, Section> sections, Map<UUID, long[]> railSections, Map<Long, Resource> resources) {
		this.version = version;
		this.sections = Collections.unmodifiableMap(sections);
		this.railSections = railSections;
		this.resources = Collections.unmodifiableMap(resources);
		final Map<Long, List<Resource>> tempSectionResources = new HashMap<>();
		resources.values().forEach(resource -> {
			for (final long sectionId : resource.getSections()) {
				tempSectionResources.computeIfAbsent(sectionId, key -> new ArrayList<>()).add(resource);
			}
		});
		tempSectionResources.replaceAll((sectionId, sectionResourceList) -> Collections.unmodifiableList(sectionResourceList));
		sectionResources = tempSectionResources;

		long hash = 17;
		for (final Section section : sections.values()) {
			hash = hash * 31 + section.id;
			hash = hash * 31 + Arrays.hashCode(section.nodes);
		}
		fingerprint = hash;
	}

	/**
	 * @return a hash of the whole network, which changes when any section does
	 */
	public long getFingerprint() {
		return fingerprint;
	}

	/**
	 * @return the resources a train on this section uses, in order of ID
	 */
	public List<Resource> getResources(long sectionId) {
		return sectionResources.getOrDefault(sectionId, Collections.emptyList());
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
			final long[] place = railSections.get(RailKeys.of(move[0], move[1]));
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

	static long mix(long value1, long value2) {
		long hash = value1 * 0x9E3779B97F4A7C15L + value2;
		hash = (hash ^ hash >>> 31) * 0xBF58476D1CE4E5B9L;
		return hash ^ hash >>> 29;
	}

	public static final class Section {

		public final long id;
		private final long[] nodes;
		/**
		 * Each {speed limit in blocks per tick, length}, in order along the section. A limit of -1 keeps the limit of the rail
		 * before it, as on platforms and sidings.
		 */
		private final List<double[]> limits;
		public final long savedRailId;
		public final SectionType type;
		private final int[] regions;
		public final boolean forwards;
		public final boolean backwards;

		Section(long id, long[] nodes, List<double[]> limits, long savedRailId, SectionType type, int[] regions, boolean forwards, boolean backwards) {
			this.id = id;
			this.nodes = nodes;
			final List<double[]> limitsCopy = new ArrayList<>();
			limits.forEach(limit -> limitsCopy.add(limit.clone()));
			this.limits = Collections.unmodifiableList(limitsCopy);
			this.savedRailId = savedRailId;
			this.type = type;
			this.regions = regions;
			this.forwards = forwards;
			this.backwards = backwards;
		}

		public long[] getNodes() {
			return nodes.clone();
		}

		public int getLimitCount() {
			return limits.size();
		}

		/**
		 * @return the speed limit of a part of the section, or -1 to keep the one before
		 */
		public double getLimit(int index) {
			return limits.get(index)[0];
		}

		public double getLimitLength(int index) {
			return limits.get(index)[1];
		}

		public int[] getRegions() {
			return regions.clone();
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
				output.writeUTF(type.name());
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
			final SectionType type = SectionType.fromName(input.readUTF());
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
		 * Rails of the path the network doesn't have, which happens when it changed since.
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
