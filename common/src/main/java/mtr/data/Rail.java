package mtr.data;

import mtr.block.BlockNode;
import mtr.block.BlockPSDAPGBase;
import mtr.block.BlockPSDTop;
import mtr.block.BlockRailwaySign;
import mtr.block.BlockRailwaySignPole;
import mtr.block.BlockRouteSignBase;
import mtr.mappings.Text;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import mtr.compat.MaterialColor;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.phys.Vec3;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

public class Rail extends SerializedDataBase {

	public final RailType railType;
	public final TransportMode transportMode;
	public final RailAngle facingStart;
	public final RailAngle facingEnd;
	/**
	 * How long trains wait before turning back on this rail in half seconds, or 0 to turn back at once.
	 */
	public int turnBackDwell;
	private final double h1, k1, r1, tStart1, tEnd1;
	private final double h2, k2, r2, tStart2, tEnd2;
	private final int yStart, yEnd;
	private final boolean reverseT1, isStraight1, reverseT2, isStraight2;

	private static final double ACCEPT_THRESHOLD = 1E-4;
	private static final double ANGLE_THRESHOLD = 1E-9;
	private static final int MIN_RADIUS = 2;
	public static final int MAX_TURN_BACK_DWELL = 7200;
	private static final int TURN_BACK_CHECK_STEPS = 256;
	private static final double TURN_BACK_TOLERANCE = 0.5;
	private static final int CABLE_CURVATURE_SCALE = 1000;
	private static final int MAX_CABLE_DIP = 8;

	private static final String KEY_H_1 = "h_1";
	private static final String KEY_K_1 = "k_1";
	private static final String KEY_H_2 = "h_2";
	private static final String KEY_K_2 = "k_2";
	private static final String KEY_R_1 = "r_1";
	private static final String KEY_R_2 = "r_2";
	private static final String KEY_T_START_1 = "t_start_1";
	private static final String KEY_T_END_1 = "t_end_1";
	private static final String KEY_T_START_2 = "t_start_2";
	private static final String KEY_T_END_2 = "t_end_2";
	private static final String KEY_Y_START = "y_start";
	private static final String KEY_Y_END = "y_end";
	private static final String KEY_REVERSE_T_1 = "reverse_t_1";
	private static final String KEY_IS_STRAIGHT_1 = "is_straight_1";
	private static final String KEY_REVERSE_T_2 = "reverse_t_2";
	private static final String KEY_IS_STRAIGHT_2 = "is_straight_2";
	private static final String KEY_RAIL_TYPE = "rail_type";
	private static final String KEY_TRANSPORT_MODE = "transport_mode";
	private static final String KEY_TURN_BACK_DWELL = "turn_back_dwell";

	// for curves:
	// x = h + r*cos(T)
	// z = k + r*sin(T)
	// for straight lines (both k and r >= 0.5):
	// x = h*T
	// z = k*T + h*r
	// for straight lines (otherwise):
	// x = h*T + k*r
	// z = k*T + h*r

	public Rail(BlockPos posStart, RailAngle facingStart, BlockPos posEnd, RailAngle facingEnd, RailType railType, TransportMode transportMode) {
		this.facingStart = facingStart;
		this.facingEnd = facingEnd;
		this.railType = railType;
		this.transportMode = transportMode;
		yStart = posStart.getY();
		yEnd = posEnd.getY();

		final int xStart = posStart.getX();
		final int zStart = posStart.getZ();
		final int xEnd = posEnd.getX();
		final int zEnd = posEnd.getZ();

		// Coordinate system translation and rotation
		final Vec3 vecDifference = new Vec3(posEnd.getX() - posStart.getX(), 0, posEnd.getZ() - posStart.getZ());
		final double vecDifferenceRotatedX = vecDifference.x * facingStart.cos + vecDifference.z * facingStart.sin;
		final double vecDifferenceRotatedZ = vecDifference.z * facingStart.cos - vecDifference.x * facingStart.sin;

		// First we check the Delta Side > 0
		// 1. If they are same angle
		// 1. a. If aligned -> Use One Segment
		// 1. b. If not aligned -> Use two Circle, r = (dv^2 + dp^2) / (4dv).
		// 2. If they are right angle -> r = min ( dx,dz ), work around, actually equation 3. can be used.
		// 3. Check if one segment and one circle is available
		// 3. a. If available -> (Segment First) r2 = dv / ( sin(diff) * tan(diff/2) ) = dv / ( 1 - cos(diff)
		// 							for case 2, diff = 90 degrees, r = dv
		//					-> (Circle First) r1 = ( dp - dv / tan(diff) ) / tan (diff/2)
		// 3. b. If not -> Use two circles of different radii (see Biarc)
		final double deltaForward = vecDifferenceRotatedZ;
		final double deltaSide = vecDifferenceRotatedX;
		if (facingStart.isParallel(facingEnd)) { // 1
			if (Math.abs(deltaForward) < ACCEPT_THRESHOLD) { // 1. a.
				h1 = facingStart.cos;
				k1 = facingStart.sin;
				if (Math.abs(h1) >= 0.5 && Math.abs(k1) >= 0.5) {
					r1 = (h1 * zStart - k1 * xStart) / h1 / h1;
					tStart1 = xStart / h1;
					tEnd1 = xEnd / h1;
				} else {
					final double div = facingStart.add(facingStart).cos;
					r1 = (h1 * zStart - k1 * xStart) / div;
					tStart1 = (h1 * xStart - k1 * zStart) / div;
					tEnd1 = (h1 * xEnd - k1 * zEnd) / div;
				}
				h2 = k2 = r2 = 0;
				reverseT1 = tStart1 > tEnd1;
				reverseT2 = false;
				isStraight1 = isStraight2 = true;
				tStart2 = tEnd2 = 0;
			} else { // 1. b
				if (Math.abs(deltaSide) > ACCEPT_THRESHOLD) {
					final double radius = (deltaForward * deltaForward + deltaSide * deltaSide) / (4 * deltaForward);
					r1 = r2 = Math.abs(radius);
					h1 = xStart - radius * facingStart.sin;
					k1 = zStart + radius * facingStart.cos;
					h2 = xEnd - radius * facingEnd.sin;
					k2 = zEnd + radius * facingEnd.cos;
					reverseT1 = deltaForward < 0 != deltaSide < 0;
					reverseT2 = !reverseT1;
					tStart1 = getTBounds(xStart, h1, zStart, k1, r1);
					tEnd1 = getTBounds(xStart + vecDifference.x / 2, h1, zStart + vecDifference.z / 2, k1, r1, tStart1, reverseT1);
					tStart2 = getTBounds(xStart + vecDifference.x / 2, h2, zStart + vecDifference.z / 2, k2, r2);
					tEnd2 = getTBounds(xEnd, h2, zEnd, k2, r2, tStart2, reverseT2);
					isStraight1 = isStraight2 = false;
				} else {
					// Banned node perpendicular to the rail nodes direction
					h1 = k1 = h2 = k2 = r1 = r2 = 0;
					tStart1 = tStart2 = tEnd1 = tEnd2 = 0;
					reverseT1 = false;
					reverseT2 = false;
					isStraight1 = isStraight2 = true;
				}
			}
		} else { // 3.
			// Check if it needs invert
			final RailAngle newFacingStart = vecDifferenceRotatedX < -ACCEPT_THRESHOLD ? facingStart.getOpposite() : facingStart;
			final RailAngle newFacingEnd = facingEnd.cos * vecDifference.x + facingEnd.sin * vecDifference.z < -ACCEPT_THRESHOLD ? facingEnd.getOpposite() : facingEnd;
			final double angleForward = Math.atan2(deltaForward, deltaSide);
			final RailAngle railAngleDifference = newFacingEnd.sub(newFacingStart);
			final double angleDifference = railAngleDifference.angleRadians;

			final boolean sameSide = Math.signum(angleForward) == Math.signum(angleDifference);
			final double absAngleForward = Math.abs(angleForward);

			// A node sitting exactly on the other node's axis would need a circle of no size, so a biarc is used instead
			final boolean segmentFirstFits = Math.abs(deltaForward / (1 - railAngleDifference.cos)) >= MIN_RADIUS;
			final boolean circleFirstFits = Math.abs((deltaSide - deltaForward / railAngleDifference.tan) / railAngleDifference.halfTan) >= MIN_RADIUS;
			// A node nearly on the other node's axis makes the circle go past the end, and the straight part would then come back
			final double circleSide = deltaSide - deltaForward / railAngleDifference.tan;
			final double circleEndSide = circleSide * (1 + railAngleDifference.cos);
			final double circleEndForward = circleSide * railAngleDifference.sin;
			final double circleEndX = xStart + circleEndSide * newFacingStart.cos - circleEndForward * newFacingStart.sin;
			final double circleEndZ = zStart + circleEndSide * newFacingStart.sin + circleEndForward * newFacingStart.cos;
			final boolean circleFirstStopsInTime = (xEnd - circleEndX) * newFacingEnd.cos + (zEnd - circleEndZ) * newFacingEnd.sin >= -ACCEPT_THRESHOLD;

			if (sameSide && segmentFirstFits && absAngleForward - Math.abs(angleDifference / 2) < ANGLE_THRESHOLD) { // Segment First
				final double offsetSide = Math.abs(deltaForward / railAngleDifference.halfTan);
				final double remainingSide = deltaSide - offsetSide;
				final double deltaXEnd = xStart + remainingSide * newFacingStart.cos;
				final double deltaZEnd = zStart + remainingSide * newFacingStart.sin;
				h1 = newFacingStart.cos;
				k1 = newFacingStart.sin;
				if (Math.abs(h1) >= 0.5 && Math.abs(k1) >= 0.5) {
					r1 = (h1 * zStart - k1 * xStart) / h1 / h1;
					tStart1 = xStart / h1;
					tEnd1 = deltaXEnd / h1;
				} else {
					final double div = newFacingStart.add(newFacingStart).cos;
					r1 = (h1 * zStart - k1 * xStart) / div;
					tStart1 = (h1 * xStart - k1 * zStart) / div;
					tEnd1 = (h1 * deltaXEnd - k1 * deltaZEnd) / div;
				}
				isStraight1 = true;
				reverseT1 = tStart1 > tEnd1;
				final double radius = deltaForward / (1 - railAngleDifference.cos);
				r2 = Math.abs(radius);
				h2 = deltaXEnd - radius * newFacingStart.sin;
				k2 = deltaZEnd + radius * newFacingStart.cos;
				reverseT2 = (deltaForward < 0);
				tStart2 = getTBounds(deltaXEnd, h2, deltaZEnd, k2, r2);
				tEnd2 = getTBounds(xEnd, h2, zEnd, k2, r2, tStart2, reverseT2);
				isStraight2 = false;
			} else if (sameSide && circleFirstFits && circleFirstStopsInTime && absAngleForward - Math.abs(angleDifference) < ANGLE_THRESHOLD) { // Circle First
				final double deltaXEnd = circleEndX;
				final double deltaZEnd = circleEndZ;
				final double radius = circleSide / railAngleDifference.halfTan;
				r1 = Math.abs(radius);
				h1 = xStart - radius * newFacingStart.sin;
				k1 = zStart + radius * newFacingStart.cos;
				isStraight1 = false;
				reverseT1 = (deltaForward < 0);
				tStart1 = getTBounds(xStart, h1, zStart, k1, r1);
				tEnd1 = getTBounds(deltaXEnd, h1, deltaZEnd, k1, r1, tStart1, reverseT1);
				h2 = newFacingEnd.cos;
				k2 = newFacingEnd.sin;
				if (Math.abs(h2) >= 0.5 && Math.abs(k2) >= 0.5) {
					r2 = (h2 * deltaZEnd - k2 * deltaXEnd) / h2 / h2;
					tStart2 = deltaXEnd / h2;
					tEnd2 = xEnd / h2;
				} else {
					final double div = newFacingEnd.add(newFacingEnd).cos;
					r2 = (h2 * deltaZEnd - k2 * deltaXEnd) / div;
					tStart2 = (h2 * deltaXEnd - k2 * deltaZEnd) / div;
					tEnd2 = (h2 * xEnd - k2 * zEnd) / div;
				}
				isStraight2 = true;
				reverseT2 = tStart2 > tEnd2;
			} else { // Curving in opposite directions, or out of the range of a single circle
				final Biarc biarc = new Biarc(xStart, zStart, xEnd, zEnd, newFacingStart, newFacingEnd);
				h1 = biarc.h1;
				k1 = biarc.k1;
				h2 = biarc.h2;
				k2 = biarc.k2;
				r1 = biarc.r1;
				r2 = biarc.r2;
				tStart1 = biarc.tStart1;
				tStart2 = biarc.tStart2;
				tEnd1 = biarc.tEnd1;
				tEnd2 = biarc.tEnd2;
				reverseT1 = biarc.reverseT1;
				reverseT2 = biarc.reverseT2;
				isStraight1 = biarc.isStraight;
				isStraight2 = biarc.isStraight;
			}
		}
	}

	public Rail(Map<String, Value> map) {
		final MessagePackHelper messagePackHelper = new MessagePackHelper(map);
		h1 = messagePackHelper.getDouble(KEY_H_1);
		k1 = messagePackHelper.getDouble(KEY_K_1);
		h2 = messagePackHelper.getDouble(KEY_H_2);
		k2 = messagePackHelper.getDouble(KEY_K_2);
		r1 = messagePackHelper.getDouble(KEY_R_1);
		r2 = messagePackHelper.getDouble(KEY_R_2);
		tStart1 = messagePackHelper.getDouble(KEY_T_START_1);
		tEnd1 = messagePackHelper.getDouble(KEY_T_END_1);
		tStart2 = messagePackHelper.getDouble(KEY_T_START_2);
		tEnd2 = messagePackHelper.getDouble(KEY_T_END_2);
		yStart = messagePackHelper.getInt(KEY_Y_START);
		yEnd = messagePackHelper.getInt(KEY_Y_END);
		reverseT1 = messagePackHelper.getBoolean(KEY_REVERSE_T_1);
		isStraight1 = messagePackHelper.getBoolean(KEY_IS_STRAIGHT_1);
		reverseT2 = messagePackHelper.getBoolean(KEY_REVERSE_T_2);
		isStraight2 = messagePackHelper.getBoolean(KEY_IS_STRAIGHT_2);
		railType = EnumHelper.valueOf(RailType.IRON, messagePackHelper.getString(KEY_RAIL_TYPE));
		transportMode = EnumHelper.valueOf(TransportMode.TRAIN, messagePackHelper.getString(KEY_TRANSPORT_MODE));
		turnBackDwell = messagePackHelper.getInt(KEY_TURN_BACK_DWELL);

		facingStart = getRailAngle(false);
		facingEnd = getRailAngle(true);
	}

	@Deprecated
	public Rail(CompoundTag compoundTag) {
		h1 = compoundTag.getDouble(KEY_H_1);
		k1 = compoundTag.getDouble(KEY_K_1);
		h2 = compoundTag.getDouble(KEY_H_2);
		k2 = compoundTag.getDouble(KEY_K_2);
		r1 = compoundTag.getDouble(KEY_R_1);
		r2 = compoundTag.getDouble(KEY_R_2);
		tStart1 = compoundTag.getDouble(KEY_T_START_1);
		tEnd1 = compoundTag.getDouble(KEY_T_END_1);
		tStart2 = compoundTag.getDouble(KEY_T_START_2);
		tEnd2 = compoundTag.getDouble(KEY_T_END_2);
		yStart = compoundTag.getInt(KEY_Y_START);
		yEnd = compoundTag.getInt(KEY_Y_END);
		reverseT1 = compoundTag.getBoolean(KEY_REVERSE_T_1);
		isStraight1 = compoundTag.getBoolean(KEY_IS_STRAIGHT_1);
		reverseT2 = compoundTag.getBoolean(KEY_REVERSE_T_2);
		isStraight2 = compoundTag.getBoolean(KEY_IS_STRAIGHT_2);
		railType = EnumHelper.valueOf(RailType.IRON, compoundTag.getString(KEY_RAIL_TYPE));
		transportMode = EnumHelper.valueOf(TransportMode.TRAIN, compoundTag.getString(KEY_TRANSPORT_MODE));

		facingStart = getRailAngle(false);
		facingEnd = getRailAngle(true);
	}

	public Rail(FriendlyByteBuf packet) {
		h1 = packet.readDouble();
		k1 = packet.readDouble();
		h2 = packet.readDouble();
		k2 = packet.readDouble();
		r1 = packet.readDouble();
		r2 = packet.readDouble();
		tStart1 = packet.readDouble();
		tEnd1 = packet.readDouble();
		tStart2 = packet.readDouble();
		tEnd2 = packet.readDouble();
		yStart = packet.readInt();
		yEnd = packet.readInt();
		reverseT1 = packet.readBoolean();
		isStraight1 = packet.readBoolean();
		reverseT2 = packet.readBoolean();
		isStraight2 = packet.readBoolean();
		railType = EnumHelper.valueOf(RailType.IRON, packet.readUtf(PACKET_STRING_READ_LENGTH));
		transportMode = EnumHelper.valueOf(TransportMode.TRAIN, packet.readUtf(PACKET_STRING_READ_LENGTH));
		turnBackDwell = packet.readInt();

		facingStart = getRailAngle(false);
		facingEnd = getRailAngle(true);
	}

	@Override
	public void toMessagePack(MessagePacker messagePacker) throws IOException {
		messagePacker.packString(KEY_H_1).packDouble(h1);
		messagePacker.packString(KEY_K_1).packDouble(k1);
		messagePacker.packString(KEY_H_2).packDouble(h2);
		messagePacker.packString(KEY_K_2).packDouble(k2);
		messagePacker.packString(KEY_R_1).packDouble(r1);
		messagePacker.packString(KEY_R_2).packDouble(r2);
		messagePacker.packString(KEY_T_START_1).packDouble(tStart1);
		messagePacker.packString(KEY_T_END_1).packDouble(tEnd1);
		messagePacker.packString(KEY_T_START_2).packDouble(tStart2);
		messagePacker.packString(KEY_T_END_2).packDouble(tEnd2);
		messagePacker.packString(KEY_Y_START).packInt(yStart);
		messagePacker.packString(KEY_Y_END).packInt(yEnd);
		messagePacker.packString(KEY_REVERSE_T_1).packBoolean(reverseT1);
		messagePacker.packString(KEY_IS_STRAIGHT_1).packBoolean(isStraight1);
		messagePacker.packString(KEY_REVERSE_T_2).packBoolean(reverseT2);
		messagePacker.packString(KEY_IS_STRAIGHT_2).packBoolean(isStraight2);
		messagePacker.packString(KEY_RAIL_TYPE).packString(railType.toString());
		messagePacker.packString(KEY_TRANSPORT_MODE).packString(transportMode.toString());
		messagePacker.packString(KEY_TURN_BACK_DWELL).packInt(turnBackDwell);
	}

	@Override
	public int messagePackLength() {
		return 19;
	}

	@Override
	public void writePacket(FriendlyByteBuf packet) {
		packet.writeDouble(h1);
		packet.writeDouble(k1);
		packet.writeDouble(h2);
		packet.writeDouble(k2);
		packet.writeDouble(r1);
		packet.writeDouble(r2);
		packet.writeDouble(tStart1);
		packet.writeDouble(tEnd1);
		packet.writeDouble(tStart2);
		packet.writeDouble(tEnd2);
		packet.writeInt(yStart);
		packet.writeInt(yEnd);
		packet.writeBoolean(reverseT1);
		packet.writeBoolean(isStraight1);
		packet.writeBoolean(reverseT2);
		packet.writeBoolean(isStraight2);
		packet.writeUtf(railType.toString());
		packet.writeUtf(transportMode.toString());
		packet.writeInt(turnBackDwell);
	}

	/**
	 * A path needs a stop time above 0 for the train to stop and reverse at all.
	 */
	public int getTurnBackDwellTime() {
		return Math.max(turnBackDwell, 1);
	}

	public Vec3 getPosition(double rawValue) {
		final double count1 = Math.abs(tEnd1 - tStart1);
		final double count2 = Math.abs(tEnd2 - tStart2);
		final double value = Mth.clamp(rawValue, 0, count1 + count2);
		final double y = getPositionY(value);

		if (value <= count1) {
			return getPositionXZ(h1, k1, r1, (reverseT1 ? -1 : 1) * value + tStart1, 0, isStraight1).add(0, y, 0);
		} else {
			return getPositionXZ(h2, k2, r2, (reverseT2 ? -1 : 1) * (value - count1) + tStart2, 0, isStraight2).add(0, y, 0);
		}
	}

	public double getLength() {
		return Math.abs(tEnd2 - tStart2) + Math.abs(tEnd1 - tStart1);
	}

	public void render(RenderRail callback, float offsetRadius1, float offsetRadius2) {
		renderSegment(h1, k1, r1, tStart1, tEnd1, 0, offsetRadius1, offsetRadius2, reverseT1, isStraight1, callback);
		renderSegment(h2, k2, r2, tStart2, tEnd2, Math.abs(tEnd1 - tStart1), offsetRadius1, offsetRadius2, reverseT2, isStraight2, callback);
	}

	/**
	 * A node is a line with two sides, so the angle of a node with rails means the facing of one of them. Always using the
	 * same one keeps the number the same on the client and the server.
	 *
	 * @param connections the rails leaving a node
	 * @return the facing of the node, or null if it has no rails
	 */
	/**
	 * Builds the rails of a node again for a new angle, each keeping its other end as it is, as the brush sets it on the
	 * server and the brush screen previews it on the client.
	 *
	 * @param rails every rail, by its start and then its end
	 */
	public static RotatedNode rotateNode(Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos pos, RailAngle angle) {
		final Map<BlockPos, Rail> connections = rails.get(pos);
		final RotatedNode rotatedNode = new RotatedNode();
		if (connections == null || connections.isEmpty()) {
			return rotatedNode;
		}
		final RailAngle referenceFacing = getNodeFacing(connections);
		for (final Map.Entry<BlockPos, Rail> entry : connections.entrySet()) {
			final BlockPos posEnd = entry.getKey();
			final Rail rail = entry.getValue();
			final Map<BlockPos, Rail> backwardsConnections = rails.get(posEnd);
			final Rail backwardsRail = backwardsConnections == null ? null : backwardsConnections.get(pos);
			if (backwardsRail == null) {
				return rotatedNode.failed("gui.mtr.invalid_orientation");
			}

			// A node has no front or back, so each rail keeps leaving from the side it already uses
			final RailAngle facing = angle.getRotatedFacing(referenceFacing, rail.getLeavingFacing());
			final RailAngle facingEnd = rail.getArrivingFacing();
			final Rail newRail = new Rail(pos, facing, posEnd, facingEnd, rail.railType, rail.transportMode);
			final Rail newBackwardsRail = new Rail(posEnd, facingEnd, pos, facing, backwardsRail.railType, rail.transportMode);
			rotatedNode.rails.put(posEnd, newRail);
			rotatedNode.backwardsRails.put(posEnd, newBackwardsRail);
			if (!newRail.goodRadius() || !newBackwardsRail.goodRadius()) {
				return rotatedNode.failed("gui.mtr.radius_too_small");
			}
			if (!newRail.isValid() || !newBackwardsRail.isValid()) {
				return rotatedNode.failed("gui.mtr.invalid_orientation");
			}
			// Curves of more than a quarter turn, which turnouts and balloon loops can have, run backwards from the start
			if (!rail.turnsBack(pos, posEnd) && (newRail.turnsBack(pos, posEnd) || newBackwardsRail.turnsBack(posEnd, pos))) {
				return rotatedNode.failed("gui.mtr.rail_would_loop");
			}
			newRail.turnBackDwell = rail.turnBackDwell;
			newBackwardsRail.turnBackDwell = backwardsRail.turnBackDwell;
		}
		return rotatedNode;
	}

	public static RailAngle getNodeFacing(Map<BlockPos, Rail> connections) {
		return connections == null || connections.isEmpty() ? null : connections.get(Collections.min(connections.keySet(), Comparator.comparingLong(BlockPos::asLong))).getLeavingFacing();
	}

	/**
	 * The facing saved with a rail can point either way along the node's line, as the shape only uses it as a line.
	 *
	 * @return the way this rail really leaves its start
	 */
	public RailAngle getLeavingFacing() {
		return matchDirection(facingStart, getRailAngle(false));
	}

	/**
	 * @return the way from the end of this rail back along it, which is how the end of a rail is given
	 */
	public RailAngle getArrivingFacing() {
		return matchDirection(facingEnd, getRailAngle(true));
	}

	private static RailAngle matchDirection(RailAngle facing, RailAngle direction) {
		return facing.cos * direction.cos + facing.sin * direction.sin >= 0 ? facing : facing.getOpposite();
	}

	/**
	 * True if the rail runs backwards anywhere along the line between its two ends.
	 */
	public boolean turnsBack(BlockPos posStart, BlockPos posEnd) {
		final double differenceX = posEnd.getX() - posStart.getX();
		final double differenceZ = posEnd.getZ() - posStart.getZ();
		final double length = getLength();
		double furthest = Double.NEGATIVE_INFINITY;
		for (int i = 0; i <= TURN_BACK_CHECK_STEPS; i++) {
			final Vec3 position = getPosition(length * i / TURN_BACK_CHECK_STEPS);
			final double progress = position.x * differenceX + position.z * differenceZ;
			if (progress < furthest - TURN_BACK_TOLERANCE * Math.hypot(differenceX, differenceZ)) {
				return true;
			}
			furthest = Math.max(furthest, progress);
		}
		return false;
	}

	public boolean goodRadius() {
		return (isStraight1 || r1 > MIN_RADIUS - ACCEPT_THRESHOLD) && (isStraight2 || r2 > MIN_RADIUS - ACCEPT_THRESHOLD);
	}

	public boolean isValid() {
		return (h1 != 0 || k1 != 0 || h2 != 0 || k2 != 0 || r1 != 0 || r2 != 0 || tStart1 != 0 || tStart2 != 0 || tEnd1 != 0 || tEnd2 != 0) && facingStart == getRailAngle(false) && facingEnd == getRailAngle(true);
	}

	private double getPositionY(double value) {
		final double length = getLength();

		if (railType.railSlopeStyle == RailType.RailSlopeStyle.CABLE) {
			if (value < 0.5) {
				return yStart;
			} else if (value > length - 0.5) {
				return yEnd;
			}

			final double offsetValue = value - 0.5;
			final double offsetLength = length - 1;
			final double posY = yStart + (yEnd - yStart) * offsetValue / offsetLength;
			final double dip = offsetLength * offsetLength / 4 / CABLE_CURVATURE_SCALE;
			return posY + (dip > MAX_CABLE_DIP ? MAX_CABLE_DIP / dip : 1) * (offsetValue - offsetLength) * offsetValue / CABLE_CURVATURE_SCALE;
		} else {
			final double intercept = length / 2;
			final double yChange;
			final double yInitial;
			final double offsetValue;

			if (value < intercept) {
				yChange = (yEnd - yStart) / 2D;
				yInitial = yStart;
				offsetValue = value;
			} else {
				yChange = (yStart - yEnd) / 2D;
				yInitial = yEnd;
				offsetValue = length - value;
			}

			return yChange * offsetValue * offsetValue / (intercept * intercept) + yInitial;
		}
	}

	private static Vec3 getPositionXZ(double h, double k, double r, double t, double radiusOffset, boolean isStraight) {
		if (isStraight) {
			return new Vec3(h * t + k * ((Math.abs(h) >= 0.5 && Math.abs(k) >= 0.5 ? 0 : r) + radiusOffset) + 0.5, 0, k * t + h * (r - radiusOffset) + 0.5);
		} else {
			return new Vec3(h + (r + radiusOffset) * Math.cos(t / r) + 0.5, 0, k + (r + radiusOffset) * Math.sin(t / r) + 0.5);
		}
	}

	private void renderSegment(double h, double k, double r, double tStart, double tEnd, double rawValueOffset, float offsetRadius1, float offsetRadius2, boolean reverseT, boolean isStraight, RenderRail callback) {
		final double count = Math.abs(tEnd - tStart);
		final double increment = count / Math.max(1, Math.round(count));

		for (double i = 0; i < count - 0.1; i += increment) {
			final double t1 = (reverseT ? -1 : 1) * i + tStart;
			final double t2 = (reverseT ? -1 : 1) * (i + increment) + tStart;
			final Vec3 corner1 = getPositionXZ(h, k, r, t1, offsetRadius1, isStraight);
			final Vec3 corner2 = getPositionXZ(h, k, r, t1, offsetRadius2, isStraight);
			final Vec3 corner3 = getPositionXZ(h, k, r, t2, offsetRadius2, isStraight);
			final Vec3 corner4 = getPositionXZ(h, k, r, t2, offsetRadius1, isStraight);

			final double y1 = getPositionY(i + rawValueOffset);
			final double y2 = getPositionY(i + increment + rawValueOffset);

			callback.renderRail(corner1.x, corner1.z, corner2.x, corner2.z, corner3.x, corner3.z, corner4.x, corner4.z, y1, y2);
		}
	}

	private RailAngle getRailAngle(boolean getEnd) {
		final boolean useSegment1 = getEnd ? Math.abs(tEnd2 - tStart2) < ACCEPT_THRESHOLD : Math.abs(tEnd1 - tStart1) >= ACCEPT_THRESHOLD;
		final double h = useSegment1 ? h1 : h2;
		final double k = useSegment1 ? k1 : k2;
		final double r = useSegment1 ? r1 : r2;
		final double t = useSegment1 ? getEnd ? tEnd1 : tStart1 : getEnd ? tEnd2 : tStart2;
		final boolean reverseT = useSegment1 ? reverseT1 : reverseT2;
		final boolean isStraight = useSegment1 ? isStraight1 : isStraight2;
		// The direction is calculated exactly, since sampling positions is unreliable for very large radii
		final double directionX = isStraight ? h : -Math.sin(t / r);
		final double directionZ = isStraight ? k : Math.cos(t / r);
		final int multiplier = reverseT != getEnd ? -1 : 1;
		return RailAngle.fromAngle(Math.toDegrees(Math.atan2(multiplier * directionZ, multiplier * directionX)));
	}

	private static double getTBounds(double x, double h, double z, double k, double r) {
		return Math.atan2(z - k, x - h) * r;
	}

	private static double getTBounds(double x, double h, double z, double k, double r, double tStart, boolean reverse) {
		final double t = getTBounds(x, h, z, k, r);
		if (t < tStart && !reverse) {
			return t + 2 * Math.PI * r;
		} else if (t > tStart && reverse) {
			return t - 2 * Math.PI * r;
		} else {
			return t;
		}
	}

	// Two tangent circles connecting any two positions and directions, with all fields being 0 if there is no usable solution
	private static class Biarc {

		private final double h1, k1, r1, tStart1, tEnd1;
		private final double h2, k2, r2, tStart2, tEnd2;
		private final boolean reverseT1, reverseT2, isStraight;

		private Biarc(int xStart, int zStart, int xEnd, int zEnd, RailAngle facingStart, RailAngle facingEnd) {
			final double differenceX = xEnd - xStart;
			final double differenceZ = zEnd - zStart;
			final double sumDirectionDifference = differenceX * (facingStart.cos + facingEnd.cos) + differenceZ * (facingStart.sin + facingEnd.sin);
			final double directionFactor = 2 * (1 - (facingStart.cos * facingEnd.cos + facingStart.sin * facingEnd.sin));
			final double differenceSquared = differenceX * differenceX + differenceZ * differenceZ;
			final double divisor = sumDirectionDifference + Math.sqrt(sumDirectionDifference * sumDirectionDifference + directionFactor * differenceSquared);
			final double tangentLength = divisor > ACCEPT_THRESHOLD ? differenceSquared / divisor : 0;

			final double xMiddle = (xStart + xEnd + tangentLength * (facingStart.cos - facingEnd.cos)) / 2;
			final double zMiddle = (zStart + zEnd + tangentLength * (facingStart.sin - facingEnd.sin)) / 2;
			final double radius1 = getSignedRadius(xMiddle - xStart, zMiddle - zStart, facingStart);
			final double radius2 = getSignedRadius(xMiddle - xEnd, zMiddle - zEnd, facingEnd);

			final double tempH1 = xStart - radius1 * facingStart.sin;
			final double tempK1 = zStart + radius1 * facingStart.cos;
			final double tempR1 = Math.abs(radius1);
			final double tempTStart1 = getTBounds(xStart, tempH1, zStart, tempK1, tempR1);
			final double tempTEnd1 = getTBounds(xMiddle, tempH1, zMiddle, tempK1, tempR1, tempTStart1, radius1 < 0);
			final double tempH2 = xEnd - radius2 * facingEnd.sin;
			final double tempK2 = zEnd + radius2 * facingEnd.cos;
			final double tempR2 = Math.abs(radius2);
			final double tempTStart2 = getTBounds(xMiddle, tempH2, zMiddle, tempK2, tempR2);
			final double tempTEnd2 = getTBounds(xEnd, tempH2, zEnd, tempK2, tempR2, tempTStart2, radius2 < 0);

			if (radius1 == 0 || radius2 == 0 || Math.abs(tempTEnd1 - tempTStart1) > Math.PI * tempR1 || Math.abs(tempTEnd2 - tempTStart2) > Math.PI * tempR2) {
				h1 = k1 = r1 = tStart1 = tEnd1 = h2 = k2 = r2 = tStart2 = tEnd2 = 0;
				reverseT1 = reverseT2 = false;
				isStraight = true;
			} else {
				h1 = tempH1;
				k1 = tempK1;
				r1 = tempR1;
				tStart1 = tempTStart1;
				tEnd1 = tempTEnd1;
				h2 = tempH2;
				k2 = tempK2;
				r2 = tempR2;
				tStart2 = tempTStart2;
				tEnd2 = tempTEnd2;
				reverseT1 = radius1 < 0;
				reverseT2 = radius2 < 0;
				isStraight = false;
			}
		}

		private static double getSignedRadius(double chordX, double chordZ, RailAngle facing) {
			final double perpendicular = chordZ * facing.cos - chordX * facing.sin;
			return Math.abs(perpendicular) < ACCEPT_THRESHOLD * ACCEPT_THRESHOLD ? 0 : (chordX * chordX + chordZ * chordZ) / (2 * perpendicular);
		}
	}

	public static class RailActions {

		private double distance;

		public final long id;
		private final Level world;
		private final UUID uuid;
		private final String playerName;
		private final RailActionType railActionType;
		private final Rail rail;
		private final int radius;
		private final int height;
		private final double length;
		private final BlockState state;
		private final boolean isSlab;
		private final Set<BlockPos> blacklistedPos = new HashSet<>();
		// Highest priority already written to each position by the bridge creator: 1 bottom half, 2 top half, 3 air above
		private final Long2IntMap bridgePlacedState = new Long2IntOpenHashMap();
		// Round tunnel and wall: sample index along the rail, positions already handled, and for the wall the blocks the tunnel of the same radius clears
		private int roundStep;
		private final LongOpenHashSet roundDonePos = new LongOpenHashSet();
		private LongOpenHashSet roundTunnelPos;
		private boolean roundTunnelCollected;

		// Distance along the rail between cross-sections, and across the rail between samples of one cross-section
		private static final double INCREMENT = 0.1;
		private static final double SIDE_INCREMENT = 0.01;
		private static final double ROUND_TUNNEL_INCREMENT = 0.1;
		private static final long TICK_BUDGET_NANOS = 2_000_000;

		public RailActions(Level world, Player player, RailActionType railActionType, Rail rail, int radius, int height, BlockState state) {
			id = new Random().nextLong();
			this.world = world;
			uuid = player.getUUID();
			playerName = player.getName().getString();
			this.railActionType = railActionType;
			this.rail = rail;
			this.radius = radius;
			this.height = height;
			this.state = state;
			isSlab = state != null && state.getBlock() instanceof SlabBlock;
			length = rail.getLength();
			distance = 0;
		}

		public boolean build() {
			switch (railActionType) {
				case BRIDGE:
					return createBridge();
				case TUNNEL:
					return createTunnel();
				case TUNNEL_WALL:
					return createTunnelWall();
				case TUNNEL_ROUND:
					return createRoundTunnel();
				case TUNNEL_WALL_ROUND:
					return createRoundTunnelWall();
				default:
					return true;
			}
		}

		public void writePacket(FriendlyByteBuf packet) {
			packet.writeLong(id);
			packet.writeUtf(playerName);
			packet.writeFloat(RailwayData.round(length, 1));
			packet.writeUtf(state == null ? "" : state.getBlock().getDescriptionId());
			packet.writeUtf(railActionType.nameTranslation);
			packet.writeInt(railActionType.color);
		}

		private boolean createTunnel() {
			return create(true, editPos -> {
				final BlockPos pos = RailwayData.newBlockPos(editPos);
				if (blacklistedPos.add(pos)) {
					setBlockIfChanged(pos, Blocks.AIR.defaultBlockState());
				}
			});
		}

		private boolean createTunnelWall() {
			return create(false, editPos -> {
				final BlockPos pos = RailwayData.newBlockPos(editPos);
				if (blacklistedPos.add(pos)) {
					setBlockIfChanged(pos, state);
				}
			});
		}

		// Slabs fill the half block the rail passes through; a full block or a double slab goes under it, and the space above is cleared
		private boolean createBridge() {
			return create(false, editPos -> {
				final BlockPos pos = RailwayData.newBlockPos(editPos);
				final boolean isTopHalf = editPos.y - Math.floor(editPos.y) >= 0.5;

				final BlockPos placePos;
				final BlockPos airPos;
				final BlockState placeState;
				if (isSlab && isTopHalf) {
					placePos = pos;
					airPos = pos.above();
					placeState = state.setValue(SlabBlock.TYPE, SlabType.BOTTOM);
				} else {
					placePos = pos.below();
					airPos = pos;
					placeState = isSlab ? state.setValue(SlabBlock.TYPE, SlabType.DOUBLE) : state;
				}

				final int slabPriority = isTopHalf ? 2 : 1;
				if (bridgePlacedState.get(placePos.asLong()) < slabPriority) {
					setBlockIfChanged(placePos, placeState);
					bridgePlacedState.put(placePos.asLong(), slabPriority);
				}
				if (bridgePlacedState.get(airPos.asLong()) < 3) {
					setBlockIfChanged(airPos, Blocks.AIR.defaultBlockState());
					bridgePlacedState.put(airPos.asLong(), 3);
				}
			});
		}

		private boolean create(boolean includeMiddle, Consumer<Vec3> consumer) {
			final long startTime = System.nanoTime();
			while (System.nanoTime() - startTime < TICK_BUDGET_NANOS) {
				final Vec3 pos1 = rail.getPosition(distance);
				distance += INCREMENT;
				final Vec3 pos2 = rail.getPosition(distance);
				final Vec3 vec3 = new Vec3(pos2.x - pos1.x, 0, pos2.z - pos1.z).normalize().yRot((float) Math.PI / 2);
				final boolean wholeNumber = Math.floor(pos1.y) == Math.ceil(pos1.y);

				// Neighbouring samples usually fall in the same block column and would repeat the same work, so only the first one in each column is used
				int lastBlockX = Integer.MIN_VALUE;
				int lastBlockZ = Integer.MIN_VALUE;
				boolean lastIsEdge = false;
				for (double x = -radius; x <= radius; x += SIDE_INCREMENT) {
					final double editX = pos1.x + vec3.x * x;
					final double editZ = pos1.z + vec3.z * x;
					final int blockX = (int) Math.floor(editX);
					final int blockZ = (int) Math.floor(editZ);
					final boolean isEdge = radius != 0 && Math.abs(x) > radius - SIDE_INCREMENT;
					if (blockX == lastBlockX && blockZ == lastBlockZ && isEdge == lastIsEdge) {
						continue;
					}
					lastBlockX = blockX;
					lastBlockZ = blockZ;
					lastIsEdge = isEdge;

					final Vec3 editPos = new Vec3(editX, pos1.y, editZ);
					if (includeMiddle || isEdge) {
						for (int y = 0; y <= height; y++) {
							if (y < height || !wholeNumber) {
								consumer.accept(editPos.add(0, y, 0));
							}
						}
					} else {
						consumer.accept(editPos.add(0, Math.max(0, wholeNumber ? height - 1 : height), 0));
					}
				}

				if (length - distance < INCREMENT) {
					showProgressMessage(100);
					return true;
				}
			}

			showProgressMessage(RailwayData.round(100 * distance / length, 1));
			return false;
		}

		// The circle's centre is (radius - 1) blocks above the rail, so on level track the lowest cleared row is the rail's own row
		private boolean createRoundTunnel() {
			return createRound(false);
		}

		// Fills the blocks outside the round tunnel of the same radius that share a face with it, including the row under the rail.
		// The tunnel's blocks are worked out first with exactly the tunnel's own sampling, so the wall can never cut into it or leave a gap.
		private boolean createRoundTunnelWall() {
			return createRound(true);
		}

		private boolean createRound(boolean isWall) {
			if (isWall && roundTunnelPos == null) {
				roundTunnelPos = new LongOpenHashSet();
			}

			final long startTime = System.nanoTime();
			while (System.nanoTime() - startTime < TICK_BUDGET_NANOS) {
				final boolean collecting = isWall && !roundTunnelCollected;
				final int outerRadius = isWall && !collecting ? radius + 1 : radius;
				final RoundSection section = new RoundSection(rail.getPosition(roundStep * ROUND_TUNNEL_INCREMENT), rail.getPosition((roundStep + 1) * ROUND_TUNNEL_INCREMENT), radius);
				roundStep++;
				final int minY = (int) Math.floor(section.centreY - outerRadius) - 1;
				final int maxY = (int) Math.ceil(section.centreY + outerRadius) + 1;

				int lastBlockX = Integer.MIN_VALUE;
				int lastBlockZ = Integer.MIN_VALUE;
				for (double x = -outerRadius; x <= outerRadius; x += ROUND_TUNNEL_INCREMENT) {
					final int blockX = (int) Math.floor(section.x + section.sideX * x);
					final int blockZ = (int) Math.floor(section.z + section.sideZ * x);
					if (blockX == lastBlockX && blockZ == lastBlockZ) {
						continue;
					}
					lastBlockX = blockX;
					lastBlockZ = blockZ;

					for (int blockY = minY; blockY <= maxY; blockY++) {
						final long posLong = BlockPos.asLong(blockX, blockY, blockZ);
						if (collecting) {
							if (section.contains(blockX, blockY, blockZ)) {
								roundTunnelPos.add(posLong);
							}
						} else if (!roundDonePos.contains(posLong)) {
							if (isWall) {
								if (!roundTunnelPos.contains(posLong) && touchesRoundTunnel(blockX, blockY, blockZ)) {
									roundDonePos.add(posLong);
									setBlockIfChanged(RailwayData.newBlockPos(blockX, blockY, blockZ), state);
								}
							} else if (section.contains(blockX, blockY, blockZ)) {
								roundDonePos.add(posLong);
								setBlockIfChanged(RailwayData.newBlockPos(blockX, blockY, blockZ), Blocks.AIR.defaultBlockState());
							}
						}
					}
				}

				if (length - roundStep * ROUND_TUNNEL_INCREMENT < ROUND_TUNNEL_INCREMENT) {
					if (collecting) {
						roundTunnelCollected = true;
						roundStep = 0;
					} else {
						roundTunnelPos = null;
						showProgressMessage(100);
						return true;
					}
				}
			}

			final float progress = (float) Math.min(1, roundStep * ROUND_TUNNEL_INCREMENT / length);
			// The wall walks the rail twice: first to find the tunnel's blocks, then to place the wall
			showProgressMessage(RailwayData.round(100 * (isWall ? (roundTunnelCollected ? 0.5F + progress / 2 : progress / 2) : progress), 1));
			return false;
		}

		private boolean touchesRoundTunnel(int x, int y, int z) {
			return roundTunnelPos.contains(BlockPos.asLong(x - 1, y, z)) || roundTunnelPos.contains(BlockPos.asLong(x + 1, y, z))
					|| roundTunnelPos.contains(BlockPos.asLong(x, y - 1, z)) || roundTunnelPos.contains(BlockPos.asLong(x, y + 1, z))
					|| roundTunnelPos.contains(BlockPos.asLong(x, y, z - 1)) || roundTunnelPos.contains(BlockPos.asLong(x, y, z + 1));
		}

		// Skips positions that already hold the target state, so going through open air or ground already cleared costs no block update
		private void setBlockIfChanged(BlockPos pos, BlockState newState) {
			final BlockState oldState = world.getBlockState(pos);
			if (oldState != newState && !isProtected(oldState.getBlock()) && world.getBlockEntity(pos) == null) {
				world.setBlockAndUpdate(pos, newState);
			}
		}

		/**
		 * Blocks the creators build around rather than replace: platform screen doors and gates, signs, and nodes of rails or
		 * of other mods such as MSD's catenary. Blocks with a block entity are left alone as well.
		 */
		private static boolean isProtected(Block block) {
			if (block instanceof BlockNode || block instanceof BlockPSDAPGBase || block instanceof BlockPSDTop || block instanceof SignBlock || block instanceof BlockRailwaySign || block instanceof BlockRailwaySignPole || block instanceof BlockRouteSignBase) {
				return true;
			}
			// Mod blocks by name, which reads the same in every Minecraft version: "block.msd.railing_stair", "block.msd.catenary_node"
			final String id = block.getDescriptionId();
			return id.contains("node") || id.startsWith("block.msd.") && (id.contains("railing") || id.contains("catenary"));
		}

		private void showProgressMessage(float percentage) {
			final Player player = world.getPlayerByUUID(uuid);
			if (player != null) {
				player.displayClientMessage(Text.translatable("gui.mtr." + railActionType.progressTranslation, percentage), true);
			}
		}

		// One cross-section of a round tunnel. Each block is judged by its own centre, measured across the rail and against the rail's height at that point along it
		private static class RoundSection {

			private final double x;
			private final double z;
			private final double centreY;
			private final double forwardX;
			private final double forwardZ;
			private final double sideX;
			private final double sideZ;
			private final double gradient;
			private final int radiusSquared;

			private RoundSection(Vec3 pos1, Vec3 pos2, int radius) {
				x = pos1.x;
				z = pos1.z;
				centreY = pos1.y + radius - 1;
				final double horizontalLength = Math.sqrt((pos2.x - pos1.x) * (pos2.x - pos1.x) + (pos2.z - pos1.z) * (pos2.z - pos1.z));
				forwardX = horizontalLength == 0 ? 1 : (pos2.x - pos1.x) / horizontalLength;
				forwardZ = horizontalLength == 0 ? 0 : (pos2.z - pos1.z) / horizontalLength;
				sideX = -forwardZ;
				sideZ = forwardX;
				gradient = horizontalLength == 0 ? 0 : (pos2.y - pos1.y) / horizontalLength;
				radiusSquared = radius * radius;
			}

			private boolean contains(int blockX, int blockY, int blockZ) {
				final double relativeX = blockX + 0.5 - x;
				final double relativeZ = blockZ + 0.5 - z;
				final double offsetSide = relativeX * sideX + relativeZ * sideZ;
				final double offsetY = blockY - (centreY + gradient * (relativeX * forwardX + relativeZ * forwardZ));
				return offsetSide * offsetSide + offsetY * offsetY < radiusSquared;
			}
		}
	}

	@FunctionalInterface
	public interface RenderRail {
		void renderRail(double x1, double z1, double x2, double z2, double x3, double z3, double x4, double z4, double y1, double y2);
	}

	public enum RailActionType {
		BRIDGE("percentage_complete_bridge", "rail_action_bridge", MaterialColor.COLOR_LIGHT_GRAY),
		TUNNEL("percentage_complete_tunnel", "rail_action_tunnel", MaterialColor.COLOR_BROWN),
		TUNNEL_WALL("percentage_complete_tunnel_wall", "rail_action_tunnel_wall", MaterialColor.COLOR_GRAY),
		TUNNEL_ROUND("percentage_complete_round_tunnel", "rail_action_round_tunnel", MaterialColor.COLOR_BROWN),
		TUNNEL_WALL_ROUND("percentage_complete_round_tunnel_wall", "rail_action_round_tunnel_wall", MaterialColor.COLOR_GRAY);

		private final String progressTranslation;
		private final String nameTranslation;
		private final int color;

		RailActionType(String progressTranslation, String nameTranslation, MaterialColor materialColor) {
			this.progressTranslation = progressTranslation;
			this.nameTranslation = nameTranslation;
			color = materialColor.col;
		}
	}

	/**
	 * The rails of a node built again for a new angle (see {@link #rotateNode}).
	 */
	public static final class RotatedNode {

		/**
		 * By the end of each rail; when it failed, the rails built until then, the last being the one that failed.
		 */
		public final Map<BlockPos, Rail> rails = new HashMap<>();
		public final Map<BlockPos, Rail> backwardsRails = new HashMap<>();
		/**
		 * The translation key of why the node can't take the angle, or null if it can.
		 */
		public String error;

		private RotatedNode failed(String error) {
			this.error = error;
			return this;
		}
	}
}
