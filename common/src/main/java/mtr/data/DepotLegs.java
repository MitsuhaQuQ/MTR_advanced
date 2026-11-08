package mtr.data;

import mtr.path.PathData;
import net.minecraft.network.FriendlyByteBuf;
import transitsim.plan.TimeSegment;
import transitsim.plan.TripPath;

import java.util.*;

/**
 * How a siding's trains get out to the first stop of a duty and back from the last (decoupling document 5d, first step):
 * the platform where its path first meets a platform, which tells the station the depot joins the line at, and for each
 * first and last stop the time from the siding and back to it. Worked out from the duties' paths at path generation and
 * sent to the clients with its result, for the running diagram; nothing runs by it.
 */
public final class DepotLegs {

	public static final DepotLegs NONE = new DepotLegs(0, Collections.emptyMap(), Collections.emptyMap());
	/**
	 * First stop platforms the siding's trains were found to have no way out to, at the last path generation.
	 */
	public final Set<Long> unreachableOut;
	/**
	 * Last stop platforms they were found to have no way back from.
	 */
	public final Set<Long> unreachableBack;
	private static final int MILLIS_PER_TICK = 50;
	private static final int MAX_PLATFORMS = 256;

	/**
	 * The first platform the siding's paths reach, or 0.
	 */
	public final long entrancePlatform;
	/**
	 * First stop platform → milliseconds from leaving the siding to reaching it.
	 */
	public final Map<Long, Long> outMillis;
	/**
	 * Last stop platform → milliseconds from leaving it to reaching the siding.
	 */
	public final Map<Long, Long> backMillis;

	public DepotLegs(long entrancePlatform, Map<Long, Long> outMillis, Map<Long, Long> backMillis) {
		this(entrancePlatform, outMillis, backMillis, Collections.emptySet(), Collections.emptySet());
	}

	public DepotLegs(long entrancePlatform, Map<Long, Long> outMillis, Map<Long, Long> backMillis, Set<Long> unreachableOut, Set<Long> unreachableBack) {
		this.entrancePlatform = entrancePlatform;
		this.outMillis = Collections.unmodifiableMap(new TreeMap<>(outMillis));
		this.backMillis = Collections.unmodifiableMap(new TreeMap<>(backMillis));
		this.unreachableOut = Collections.unmodifiableSet(limit(unreachableOut, outMillis.keySet()));
		this.unreachableBack = Collections.unmodifiableSet(limit(unreachableBack, backMillis.keySet()));
	}

	/**
	 * @return the platforms with no way, leaving out any with a way measured, at most as many as are written
	 */
	private static Set<Long> limit(Set<Long> unreachable, Set<Long> measured) {
		final Set<Long> set = new TreeSet<>();
		unreachable.stream().filter(platformId -> !measured.contains(platformId)).limit(MAX_PLATFORMS).forEach(set::add);
		return set;
	}

	/**
	 * @return these legs with the platforms the path generation found no way out to, or back from
	 */
	public DepotLegs withUnreachable(Set<Long> out, Set<Long> back) {
		return new DepotLegs(entrancePlatform, outMillis, backMillis, out, back);
	}

	/**
	 * From the duties' paths of a siding: each out to its first stop and, unless it stays on the line, back from its last.
	 * Where paths share a stop the quickest counts.
	 */
	public static DepotLegs of(Collection<TripPath<PathData>> tripPaths) {
		final Map<Long, Integer> entrances = new HashMap<>();
		final Map<Long, Long> out = new TreeMap<>();
		final Map<Long, Long> back = new TreeMap<>();
		for (final TripPath<PathData> tripPath : tripPaths) {
			if (!tripPath.isDuty() || tripPath.timeSegments.isEmpty()) {
				continue;
			}
			final List<TimeSegment> segments = tripPath.timeSegments;
			// The first platform the path runs over, passing or not, is where the depot joins the line
			segments.stream().filter(segment -> segment.savedRailBaseId != 0).findFirst().ifPresent(segment -> entrances.merge(segment.savedRailBaseId, 1, Integer::sum));
			TimeSegment firstStop = null;
			TimeSegment lastStop = null;
			for (final TimeSegment segment : segments) {
				if (segment.savedRailBaseId != 0 && !segment.pass) {
					if (firstStop == null) {
						firstStop = segment;
					}
					lastStop = segment;
				}
			}
			if (firstStop != null) {
				out.merge(firstStop.savedRailBaseId, tripPath.stopTimes.getLeadMillis(), Math::min);
			}
			if (lastStop != null && !tripPath.staysOnLine && lastStop != firstStop) {
				final long millis = (long) ((segments.get(segments.size() - 1).endTime - lastStop.endTime) * MILLIS_PER_TICK);
				if (millis > 0) {
					back.merge(lastStop.savedRailBaseId, millis, Math::min);
				}
			}
		}
		final long entrance = entrances.entrySet().stream().max(Map.Entry.<Long, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder()))).map(Map.Entry::getKey).orElse(0L);
		return new DepotLegs(entrance, out, back);
	}

	/**
	 * For saves: the entrance, then the count and pairs of the ways out, then of the ways back.
	 */
	public List<Long> toList() {
		final List<Long> list = new ArrayList<>();
		list.add(entrancePlatform);
		list.add((long) outMillis.size());
		outMillis.forEach((platformId, millis) -> {
			list.add(platformId);
			list.add(millis);
		});
		list.add((long) backMillis.size());
		backMillis.forEach((platformId, millis) -> {
			list.add(platformId);
			list.add(millis);
		});
		list.add((long) unreachableOut.size());
		list.addAll(unreachableOut);
		list.add((long) unreachableBack.size());
		list.addAll(unreachableBack);
		return list;
	}

	/**
	 * @return as saved by {@link #toList}, or {@link #NONE} if it doesn't read
	 */
	public static DepotLegs fromList(List<Long> list) {
		try {
			int index = 0;
			final long entrance = list.get(index++);
			final Map<Long, Long> out = new TreeMap<>();
			final int outCount = Math.min(list.get(index++).intValue(), MAX_PLATFORMS);
			for (int i = 0; i < outCount; i++) {
				out.put(list.get(index++), list.get(index++));
			}
			final Map<Long, Long> back = new TreeMap<>();
			final int backCount = Math.min(list.get(index++).intValue(), MAX_PLATFORMS);
			for (int i = 0; i < backCount; i++) {
				back.put(list.get(index++), list.get(index++));
			}
			// Saved before ways found missing were kept: none
			final Set<Long> unreachableOut = new TreeSet<>();
			final Set<Long> unreachableBack = new TreeSet<>();
			if (index < list.size()) {
				for (int i = Math.min(list.get(index++).intValue(), MAX_PLATFORMS); i > 0; i--) {
					unreachableOut.add(list.get(index++));
				}
				for (int i = Math.min(list.get(index++).intValue(), MAX_PLATFORMS); i > 0; i--) {
					unreachableBack.add(list.get(index++));
				}
			}
			return new DepotLegs(entrance, out, back, unreachableOut, unreachableBack);
		} catch (IndexOutOfBoundsException | NullPointerException e) {
			return NONE;
		}
	}

	public void writePacket(FriendlyByteBuf packet) {
		packet.writeLong(entrancePlatform);
		writeMap(packet, outMillis);
		writeMap(packet, backMillis);
		writeSet(packet, unreachableOut);
		writeSet(packet, unreachableBack);
	}

	public static DepotLegs readPacket(FriendlyByteBuf packet) {
		final long entrance = packet.readLong();
		final Map<Long, Long> out = readMap(packet);
		final Map<Long, Long> back = readMap(packet);
		final Set<Long> unreachableOut = readSet(packet);
		final Set<Long> unreachableBack = readSet(packet);
		return new DepotLegs(entrance, out, back, unreachableOut, unreachableBack);
	}

	private static void writeSet(FriendlyByteBuf packet, Set<Long> set) {
		packet.writeInt(set.size());
		set.forEach(packet::writeLong);
	}

	private static Set<Long> readSet(FriendlyByteBuf packet) {
		final int count = packet.readInt();
		if (count < 0 || count > MAX_PLATFORMS) {
			throw new IllegalArgumentException("Too many platforms: " + count);
		}
		final Set<Long> set = new TreeSet<>();
		for (int i = 0; i < count; i++) {
			set.add(packet.readLong());
		}
		return set;
	}

	private static void writeMap(FriendlyByteBuf packet, Map<Long, Long> map) {
		final int count = Math.min(map.size(), MAX_PLATFORMS);
		packet.writeInt(count);
		map.entrySet().stream().limit(count).forEach(entry -> {
			packet.writeLong(entry.getKey());
			packet.writeLong(entry.getValue());
		});
	}

	private static Map<Long, Long> readMap(FriendlyByteBuf packet) {
		final int count = packet.readInt();
		if (count < 0 || count > MAX_PLATFORMS) {
			throw new IllegalArgumentException("Too many platforms: " + count);
		}
		final Map<Long, Long> map = new TreeMap<>();
		for (int i = 0; i < count; i++) {
			map.put(packet.readLong(), packet.readLong());
		}
		return map;
	}
}
