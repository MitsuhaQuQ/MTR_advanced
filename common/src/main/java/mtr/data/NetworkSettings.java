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

	private static final String KEY_MIN_REVERSE_MILLIS = "min_reverse_millis";
	private static final String KEY_EARLY_THRESHOLD_MILLIS = "early_threshold_millis";

	public NetworkSettings() {
	}

	public NetworkSettings(Map<String, Value> map) {
		final MessagePackHelper messagePackHelper = new MessagePackHelper(map);
		minReverseMillis = clamp(messagePackHelper.getLong(KEY_MIN_REVERSE_MILLIS, DEFAULT_MIN_REVERSE_MILLIS));
		earlyThresholdMillis = clamp(messagePackHelper.getLong(KEY_EARLY_THRESHOLD_MILLIS, DEFAULT_EARLY_THRESHOLD_MILLIS));
	}

	public void read(FriendlyByteBuf packet) {
		minReverseMillis = clamp(packet.readLong());
		earlyThresholdMillis = clamp(packet.readLong());
	}

	public void copyFrom(NetworkSettings other) {
		minReverseMillis = other.minReverseMillis;
		earlyThresholdMillis = other.earlyThresholdMillis;
	}

	@Override
	public void toMessagePack(MessagePacker messagePacker) throws IOException {
		messagePacker.packString(KEY_MIN_REVERSE_MILLIS).packLong(minReverseMillis);
		messagePacker.packString(KEY_EARLY_THRESHOLD_MILLIS).packLong(earlyThresholdMillis);
	}

	@Override
	public int messagePackLength() {
		return 2;
	}

	@Override
	public void writePacket(FriendlyByteBuf packet) {
		packet.writeLong(minReverseMillis);
		packet.writeLong(earlyThresholdMillis);
	}

	@Override
	public boolean equals(Object object) {
		return object instanceof NetworkSettings && ((NetworkSettings) object).minReverseMillis == minReverseMillis && ((NetworkSettings) object).earlyThresholdMillis == earlyThresholdMillis;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(minReverseMillis) * 31 + Long.hashCode(earlyThresholdMillis);
	}

	public static long clamp(long millis) {
		return Math.max(0, Math.min(MAX_MILLIS, millis));
	}
}
