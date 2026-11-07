package mtr.data;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A platform: a stretch of rail between two nodes where trains stop, with its dwell time. Routes are lists of
 * platforms.
 */
public final class Platform extends SavedRailBase {

	/**
	 * The station this platform belongs to where station areas overlap, or 0 to choose automatically.
	 */
	public long stationId;
	/**
	 * Services can leave or join a route here part of the way along, running through to or from another route. See
	 * {@link mtr.advanced.TimetableBoundaries}.
	 */
	public boolean timetableBoundary;
	/**
	 * A passing track (a siding road at a station, 待避线) rather than a main line track. Players set it, so it is never
	 * guessed: the editor's track picker and track diagram tell the two apart, and only stations with a passing track can
	 * have a train overtaken.
	 */
	public boolean passingTrack;
	/**
	 * How fast trains may pass over the platform, as a speed label in km/h (optimisation round 3b, step 2), or 0 for the
	 * default ({@link PlatformSpeeds#DEFAULT_KMH}). A trait of the track, like its length.
	 */
	public int passSpeed;

	private static final String KEY_DWELL_TIME = "dwell_time";
	private static final String KEY_PASS_SPEED = "pass_speed";
	private static final String KEY_STATION_ID = "station_id";
	private static final String KEY_TIMETABLE_BOUNDARY = "timetable_boundary";
	private static final String KEY_PASSING_TRACK = "passing_track";

	public Platform(long id, TransportMode transportMode, BlockPos pos1, BlockPos pos2) {
		super(id, transportMode, pos1, pos2);
	}

	public Platform(TransportMode transportMode, BlockPos pos1, BlockPos pos2) {
		super(transportMode, pos1, pos2);
	}

	public Platform(Map<String, Value> map) {
		super(map);
		final MessagePackHelper messagePackHelper = new MessagePackHelper(map);
		stationId = messagePackHelper.getLong(KEY_STATION_ID);
		timetableBoundary = messagePackHelper.getBoolean(KEY_TIMETABLE_BOUNDARY);
		passingTrack = messagePackHelper.getBoolean(KEY_PASSING_TRACK);
		passSpeed = Math.max(0, messagePackHelper.getInt(KEY_PASS_SPEED, 0));
	}

	@Deprecated
	public Platform(CompoundTag compoundTag) {
		super(compoundTag);
	}

	public Platform(FriendlyByteBuf packet) {
		super(packet);
		stationId = packet.readLong();
		timetableBoundary = packet.readBoolean();
		passingTrack = packet.readBoolean();
		passSpeed = packet.readInt();
	}

	@Override
	public void toMessagePack(MessagePacker messagePacker) throws IOException {
		super.toMessagePack(messagePacker);
		messagePacker.packString(KEY_STATION_ID).packLong(stationId);
		messagePacker.packString(KEY_TIMETABLE_BOUNDARY).packBoolean(timetableBoundary);
		messagePacker.packString(KEY_PASSING_TRACK).packBoolean(passingTrack);
		messagePacker.packString(KEY_PASS_SPEED).packInt(passSpeed);
	}

	@Override
	public int messagePackLength() {
		return super.messagePackLength() + 4;
	}

	@Override
	public void writePacket(FriendlyByteBuf packet) {
		super.writePacket(packet);
		packet.writeLong(stationId);
		packet.writeBoolean(timetableBoundary);
		packet.writeBoolean(passingTrack);
		packet.writeInt(passSpeed);
	}

	@Override
	public void update(String key, FriendlyByteBuf packet) {
		if (KEY_STATION_ID.equals(key)) {
			stationId = packet.readLong();
		} else if (KEY_TIMETABLE_BOUNDARY.equals(key)) {
			timetableBoundary = packet.readBoolean();
		} else if (KEY_PASSING_TRACK.equals(key)) {
			passingTrack = packet.readBoolean();
		} else if (KEY_PASS_SPEED.equals(key)) {
			passSpeed = Math.max(0, packet.readInt());
		} else if (KEY_DWELL_TIME.equals(key)) {
			name = packet.readUtf(PACKET_STRING_READ_LENGTH);
			color = packet.readInt();
			dwellTime = packet.readInt();
			dwellTime = transportMode.continuousMovement ? 1 : dwellTime;
		} else {
			super.update(key, packet);
		}
	}

	public void setStationId(long newStationId, Consumer<FriendlyByteBuf> sendPacket) {
		stationId = newStationId;
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_STATION_ID);
		packet.writeLong(stationId);
		sendPacket.accept(packet);
	}

	public void setTimetableBoundary(boolean newTimetableBoundary, Consumer<FriendlyByteBuf> sendPacket) {
		timetableBoundary = newTimetableBoundary;
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_TIMETABLE_BOUNDARY);
		packet.writeBoolean(timetableBoundary);
		sendPacket.accept(packet);
	}

	public void setPassingTrack(boolean newPassingTrack, Consumer<FriendlyByteBuf> sendPacket) {
		passingTrack = newPassingTrack;
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_PASSING_TRACK);
		packet.writeBoolean(passingTrack);
		sendPacket.accept(packet);
	}

	public void setPassSpeed(int newPassSpeed, Consumer<FriendlyByteBuf> sendPacket) {
		passSpeed = Math.max(0, newPassSpeed);
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_PASS_SPEED);
		packet.writeInt(passSpeed);
		sendPacket.accept(packet);
	}

	/**
	 * @return how fast trains may pass over the platform, as a speed label in km/h
	 */
	public int getPassSpeedKmh() {
		return passSpeed > 0 ? passSpeed : PlatformSpeeds.DEFAULT_KMH;
	}

	public void setDwellTime(int newDwellTime, Consumer<FriendlyByteBuf> sendPacket) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_DWELL_TIME);
		packet.writeUtf(name);
		packet.writeInt(color);
		writeDwellTimePacket(packet, newDwellTime);
		sendPacket.accept(packet);
	}
}
