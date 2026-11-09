package mtr.data;

import net.minecraft.network.FriendlyByteBuf;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.Map;

/**
 * Settings of a dimension's whole network (optimisation round 4), kept in its save, sent to every player and changed from
 * the web editor by players who may edit.
 */
public final class NetworkSettings extends SerializedDataBase {

	/**
	 * The default least time to change ends: the 30 seconds trains always had to turn round.
	 */
	public static final long DEFAULT_MIN_REVERSE_MILLIS = transitsim.planning.DutyGenerator.MIN_TURNAROUND_MILLIS;
	/**
	 * The default lead a train early on its way may keep before it slows down its cruise (round 4, step 5).
	 */
	public static final long DEFAULT_EARLY_THRESHOLD_MILLIS = 30000;
	/**
	 * The longest either may be set to.
	 */
	public static final long MAX_MILLIS = 600000;
	/**
	 * Its only entry in the save.
	 */
	public static final long ID = 0;

	/**
	 * The least time a train takes to change ends where it turns back (换端最短时间), which plans allow everywhere.
	 */
	public long minReverseMillis = DEFAULT_MIN_REVERSE_MILLIS;
	/**
	 * How early a train may run before it slows down its cruise rather than waiting at the platform (round 4, step 5).
	 */
	public long earlyThresholdMillis = DEFAULT_EARLY_THRESHOLD_MILLIS;
	/**
	 * From how long a wait for the next service a train waits on a turn back rail rather than at the platform (运用补完
	 * step 2), for the whole network; stations may have their own, and a depot's rule its own again.
	 */
	public long waitThresholdMillis = DEFAULT_WAIT_THRESHOLD_MILLIS;
	/**
	 * Station to its own wait threshold, only the stations set.
	 */
	public final Map<Long, Long> stationWaitThresholds = new java.util.TreeMap<>();

	public static final long DEFAULT_WAIT_THRESHOLD_MILLIS = transitsim.sim.TrainService.LONG_TURN_BACK_WAIT_MILLIS;
	public static final long MAX_WAIT_THRESHOLD_MILLIS = 3600000;
	private static final int MAX_STATION_THRESHOLDS = 4096;

	private static final String KEY_MIN_REVERSE_MILLIS = "min_reverse_millis";
	private static final String KEY_EARLY_THRESHOLD_MILLIS = "early_threshold_millis";
	private static final String KEY_WAIT_THRESHOLD_MILLIS = "wait_threshold_millis";
	private static final String KEY_STATION_WAIT_THRESHOLDS = "station_wait_thresholds";

	public NetworkSettings() {
	}

	public NetworkSettings(Map<String, Value> map) {
		final MessagePackHelper messagePackHelper = new MessagePackHelper(map);
		minReverseMillis = clamp(messagePackHelper.getLong(KEY_MIN_REVERSE_MILLIS, DEFAULT_MIN_REVERSE_MILLIS));
		earlyThresholdMillis = clamp(messagePackHelper.getLong(KEY_EARLY_THRESHOLD_MILLIS, DEFAULT_EARLY_THRESHOLD_MILLIS));
		waitThresholdMillis = clampWait(messagePackHelper.getLong(KEY_WAIT_THRESHOLD_MILLIS, DEFAULT_WAIT_THRESHOLD_MILLIS));
		messagePackHelper.iterateMapValue(KEY_STATION_WAIT_THRESHOLDS, entry -> {
			if (stationWaitThresholds.size() < MAX_STATION_THRESHOLDS) {
				stationWaitThresholds.put(Long.parseLong(entry.getKey().asStringValue().asString()), clampWait(entry.getValue().asIntegerValue().asLong()));
			}
		});
	}

	/**
	 * @return the wait threshold of a station: its own, or the network one
	 */
	public long getWaitThreshold(long stationId) {
		return stationWaitThresholds.getOrDefault(stationId, waitThresholdMillis);
	}

	public void read(FriendlyByteBuf packet) {
		minReverseMillis = clamp(packet.readLong());
		earlyThresholdMillis = clamp(packet.readLong());
		waitThresholdMillis = clampWait(packet.readLong());
		stationWaitThresholds.clear();
		final int count = packet.readVarInt();
		for (int i = 0; i < count; i++) {
			final long stationId = packet.readLong();
			final long millis = clampWait(packet.readLong());
			if (i < MAX_STATION_THRESHOLDS) {
				stationWaitThresholds.put(stationId, millis);
			}
		}
	}

	public void copyFrom(NetworkSettings other) {
		minReverseMillis = other.minReverseMillis;
		earlyThresholdMillis = other.earlyThresholdMillis;
		waitThresholdMillis = other.waitThresholdMillis;
		stationWaitThresholds.clear();
		stationWaitThresholds.putAll(other.stationWaitThresholds);
	}

	@Override
	public void toMessagePack(MessagePacker messagePacker) throws IOException {
		messagePacker.packString(KEY_MIN_REVERSE_MILLIS).packLong(minReverseMillis);
		messagePacker.packString(KEY_EARLY_THRESHOLD_MILLIS).packLong(earlyThresholdMillis);
		messagePacker.packString(KEY_WAIT_THRESHOLD_MILLIS).packLong(waitThresholdMillis);
		messagePacker.packString(KEY_STATION_WAIT_THRESHOLDS).packMapHeader(stationWaitThresholds.size());
		for (final Map.Entry<Long, Long> entry : stationWaitThresholds.entrySet()) {
			messagePacker.packString(String.valueOf(entry.getKey())).packLong(entry.getValue());
		}
	}

	@Override
	public int messagePackLength() {
		return 4;
	}

	@Override
	public void writePacket(FriendlyByteBuf packet) {
		packet.writeLong(minReverseMillis);
		packet.writeLong(earlyThresholdMillis);
		packet.writeLong(waitThresholdMillis);
		packet.writeVarInt(stationWaitThresholds.size());
		stationWaitThresholds.forEach((stationId, millis) -> {
			packet.writeLong(stationId);
			packet.writeLong(millis);
		});
	}

	@Override
	public boolean equals(Object object) {
		return object instanceof NetworkSettings && ((NetworkSettings) object).minReverseMillis == minReverseMillis && ((NetworkSettings) object).earlyThresholdMillis == earlyThresholdMillis && ((NetworkSettings) object).waitThresholdMillis == waitThresholdMillis && ((NetworkSettings) object).stationWaitThresholds.equals(stationWaitThresholds);
	}

	@Override
	public int hashCode() {
		return ((Long.hashCode(minReverseMillis) * 31 + Long.hashCode(earlyThresholdMillis)) * 31 + Long.hashCode(waitThresholdMillis)) * 31 + stationWaitThresholds.hashCode();
	}

	public static long clampWait(long millis) {
		return Math.max(0, Math.min(MAX_WAIT_THRESHOLD_MILLIS, millis));
	}

	public static long clamp(long millis) {
		return Math.max(0, Math.min(MAX_MILLIS, millis));
	}
}
