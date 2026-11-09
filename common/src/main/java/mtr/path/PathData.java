package mtr.path;

import mtr.data.MessagePackHelper;
import mtr.data.Rail;
import mtr.data.RailType;
import mtr.data.RailwayData;
import mtr.data.SerializedDataBase;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.Value;
import transitsim.plan.PlanTrack;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One rail on the path of a train, with the platform or siding it is part of (if any), the dwell time there and
 * which stop it is. A siding's paths are lists of these; it implements the component's {@code PlanTrack}.
 */
public class PathData extends SerializedDataBase implements PlanTrack {

	public final Rail rail;
	public long savedRailBaseId;
	public int dwellTime;
	public int stopIndex;
	/**
	 * The speed limit of the route this rail is a part of, in blocks per tick, or 0 for none: trains run it no faster than
	 * this nor than the rail allows (see {@link #getMaxSpeed}).
	 */
	public float speedCap;

	public final BlockPos startingPos;
	private final BlockPos endingPos;

	private static final String KEY_RAIL = "rail";
	private static final String KEY_SAVED_RAIL_BASE_ID = "saved_rail_base_id";
	private static final String KEY_DWELL_TIME = "dwell_time";
	private static final String KEY_STOP_INDEX = "stop_index";
	private static final String KEY_STARTING_POS = "starting_pos";
	private static final String KEY_ENDING_POS = "ending_pos";
	private static final String KEY_SPEED_CAP = "speed_cap";

	public PathData(Rail rail, long savedRailBaseId, int dwellTime, BlockPos startingPos, BlockPos endingPos, int stopIndex) {
		this.rail = rail;
		this.savedRailBaseId = savedRailBaseId;
		this.dwellTime = dwellTime;
		this.startingPos = startingPos;
		this.endingPos = endingPos;
		this.stopIndex = stopIndex;
	}

	public PathData(Map<String, Value> map) {
		final MessagePackHelper messagePackHelper = new MessagePackHelper(map);
		rail = new Rail(RailwayData.castMessagePackValueToSKMap(map.get(KEY_RAIL)));
		savedRailBaseId = messagePackHelper.getLong(KEY_SAVED_RAIL_BASE_ID);
		dwellTime = messagePackHelper.getInt(KEY_DWELL_TIME);
		stopIndex = messagePackHelper.getInt(KEY_STOP_INDEX);
		startingPos = BlockPos.of(messagePackHelper.getLong(KEY_STARTING_POS));
		endingPos = BlockPos.of(messagePackHelper.getLong(KEY_ENDING_POS));
		speedCap = messagePackHelper.getFloat(KEY_SPEED_CAP, 0);
	}

	@Deprecated
	public PathData(CompoundTag compoundTag) {
		rail = new Rail(compoundTag.getCompound(KEY_RAIL));
		savedRailBaseId = compoundTag.getLong(KEY_SAVED_RAIL_BASE_ID);
		dwellTime = compoundTag.getInt(KEY_DWELL_TIME);
		stopIndex = compoundTag.getInt(KEY_STOP_INDEX);
		startingPos = BlockPos.of(compoundTag.getLong(KEY_STARTING_POS));
		endingPos = BlockPos.of(compoundTag.getLong(KEY_ENDING_POS));
	}

	public PathData(FriendlyByteBuf packet) {
		this(new Rail(packet), packet);
	}

	/**
	 * Reads what {@link #writePacketWithoutRail} writes, with the rail read apart.
	 */
	public PathData(Rail rail, FriendlyByteBuf packet) {
		this.rail = rail;
		savedRailBaseId = packet.readLong();
		dwellTime = packet.readInt();
		stopIndex = packet.readInt();
		startingPos = BlockPos.of(packet.readLong());
		endingPos = BlockPos.of(packet.readLong());
		speedCap = packet.readFloat();
	}

	@Override
	public void toMessagePack(MessagePacker messagePacker) throws IOException {
		messagePacker.packString(KEY_RAIL);
		messagePacker.packMapHeader(rail.messagePackLength());
		rail.toMessagePack(messagePacker);

		messagePacker.packString(KEY_SAVED_RAIL_BASE_ID).packLong(savedRailBaseId);
		messagePacker.packString(KEY_DWELL_TIME).packInt(dwellTime);
		messagePacker.packString(KEY_STOP_INDEX).packInt(stopIndex);
		messagePacker.packString(KEY_STARTING_POS).packLong(startingPos.asLong());
		messagePacker.packString(KEY_ENDING_POS).packLong(endingPos.asLong());
		if (speedCap > 0) {
			messagePacker.packString(KEY_SPEED_CAP).packFloat(speedCap);
		}
	}

	@Override
	public int messagePackLength() {
		return speedCap > 0 ? 7 : 6;
	}

	@Override
	public void writePacket(FriendlyByteBuf packet) {
		rail.writePacket(packet);
		writePacketWithoutRail(packet);
	}

	/**
	 * Writes all but the rail, for a path that writes each of its rails once however often it runs it (2026-11-09).
	 */
	public void writePacketWithoutRail(FriendlyByteBuf packet) {
		packet.writeLong(savedRailBaseId);
		packet.writeInt(dwellTime);
		packet.writeInt(stopIndex);
		packet.writeLong(startingPos.asLong());
		packet.writeLong(endingPos.asLong());
		packet.writeFloat(speedCap);
	}

	/**
	 * @return how fast a train may run this rail: the rail's speed limit, or the route's where that is lower
	 */
	public float getMaxSpeed() {
		final float railSpeed = mtr.data.SpeedRules.label(rail.railType, rail.transportMode, startingPos.asLong(), endingPos.asLong());
		return speedCap > 0 ? Math.min(railSpeed, speedCap) : railSpeed;
	}

	/**
	 * @return how fast a train may run this rail: the physical limit of the rail's or the route's speed label
	 * (optimisation document 5.6); {@link #getMaxSpeed} stays the label, which signal blocks are cut by
	 */
	public float getRunSpeed() {
		return mtr.data.SpeedRules.run(getMaxSpeed(), rail.transportMode);
	}

	/**
	 * @return the speed timetables are planned at on this rail
	 */
	public float getPlanSpeed() {
		return mtr.data.SpeedRules.plan(getMaxSpeed(), rail.transportMode);
	}

	/**
	 * @return a copy of this rail of the path with another stop, keeping the route's speed limit
	 */
	public PathData copy(long newSavedRailBaseId, int newDwellTime) {
		final PathData copy = new PathData(rail, newSavedRailBaseId, newDwellTime, startingPos, endingPos, stopIndex);
		copy.speedCap = speedCap;
		return copy;
	}

	/**
	 * Sets a route's speed limit on rails of its path (decoupling document, line speed limits).
	 *
	 * @param speedLimit in km/h, or 0 for none
	 */
	public static void capSpeed(List<PathData> path, int speedLimit) {
		final float cap = speedLimit > 0 ? speedLimit / 3.6F / 20 : 0;
		path.forEach(pathData -> pathData.speedCap = cap);
	}

	public BlockPos getLastPos() {
		return endingPos;
	}

	public boolean isSameRail(PathData pathData) {
		return startingPos.equals(pathData.startingPos) && endingPos.equals(pathData.endingPos);
	}

	public boolean isOppositeRail(PathData pathData) {
		return startingPos.equals(pathData.endingPos) && endingPos.equals(pathData.startingPos);
	}

	public UUID getRailProduct() {
		return getRailProduct(startingPos, endingPos);
	}

	@Override
	public double getTrackLength() {
		return rail.getLength();
	}

	@Override
	public int getDwellTime() {
		return dwellTime;
	}

	@Override
	public boolean isPlatformTrack() {
		return rail.railType == RailType.PLATFORM;
	}

	@Override
	public boolean isSidingTrack() {
		return rail.railType == RailType.SIDING;
	}

	@Override
	public long getStopId() {
		return savedRailBaseId;
	}

	/**
	 * Short of the exit end of a platform set to stop trains elsewhere (optimisation round 4), as the platform's setting
	 * has it now ({@link mtr.data.PlatformStops}).
	 */
	@Override
	public double getStopOffset(double trainLength) {
		if (rail.railType != RailType.PLATFORM || savedRailBaseId == 0) {
			return 0;
		}
		final int setting = mtr.data.PlatformStops.get(savedRailBaseId);
		return setting == transitsim.plan.StopPositions.EXIT_END ? 0 : transitsim.plan.StopPositions.getOffset(setting, rail.getLength(), trainLength);
	}

	@Override
	public int getStopIndex() {
		return stopIndex;
	}

	@Override
	public void setStopIndex(int stopIndex) {
		this.stopIndex = stopIndex;
	}

	@Override
	public boolean isOppositeOf(PlanTrack track) {
		return track instanceof PathData && isOppositeRail((PathData) track);
	}

	@Override
	public UUID getTrackKey() {
		return getRailProduct();
	}

	public static UUID getRailProduct(BlockPos startingPos, BlockPos endingPos) {
		final long startingPosLong = startingPos.asLong();
		final long endingPosLong = endingPos.asLong();
		return new UUID(Math.min(startingPosLong, endingPosLong), Math.max(startingPosLong, endingPosLong));
	}
}
