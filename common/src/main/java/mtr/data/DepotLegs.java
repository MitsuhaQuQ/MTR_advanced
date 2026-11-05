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
		this.entrancePlatform = entrancePlatform;
		this.outMillis = Collections.unmodifiableMap(new TreeMap<>(outMillis));
		this.backMillis = Collections.unmodifiableMap(new TreeMap<>(backMillis));
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

	public void writePacket(FriendlyByteBuf packet) {
		packet.writeLong(entrancePlatform);
		writeMap(packet, outMillis);
		writeMap(packet, backMillis);
	}

	public static DepotLegs readPacket(FriendlyByteBuf packet) {
		final long entrance = packet.readLong();
		final Map<Long, Long> out = readMap(packet);
		final Map<Long, Long> back = readMap(packet);
		return new DepotLegs(entrance, out, back);
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
