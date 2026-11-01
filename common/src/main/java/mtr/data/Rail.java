package mtr.data;

import mtr.block.BlockNode;
import mtr.mappings.Text;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import mtr.compat.MaterialColor;
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
	private final double h1, k1, r1, tStart1, tEnd1;
	private final double h2, k2, r2, tStart2, tEnd2;
	private final int yStart, yEnd;
	private final boolean reverseT1, isStraight1, reverseT2, isStraight2;

	private static final double ACCEPT_THRESHOLD = 1E-4;
	private static final double ANGLE_THRESHOLD = 1E-9;
	private static final int MIN_RADIUS = 2;
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
			} else if (sameSide && circleFirstFits && absAngleForward - Math.abs(angleDifference) < ANGLE_THRESHOLD) { // Circle First
				final double crossSide = deltaForward / railAngleDifference.tan;
				final double remainingSide = (deltaSide - crossSide) * (1 + railAngleDifference.cos);
				final double remainingForward = (deltaSide - crossSide) * (railAngleDifference.sin);
				final double deltaXEnd = xStart + remainingSide * newFacingStart.cos - remainingForward * newFacingStart.sin;
				final double deltaZEnd = zStart + remainingSide * newFacingStart.sin + remainingForward * newFacingStart.cos;
				final double radius = (deltaSide - deltaForward / railAngleDifference.tan) / railAngleDifference.halfTan;
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
	}

	@Override
	public int messagePackLength() {
		return 18;
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

		private static final double INCREMENT = 0.01;

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
				if (!blacklistedPos.contains(pos) && canPlace(world, pos)) {
					world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
					blacklistedPos.add(pos);
				}
			});
		}

		private boolean createTunnelWall() {
			return create(false, editPos -> {
				final BlockPos pos = RailwayData.newBlockPos(editPos);
				if (!blacklistedPos.contains(pos) && canPlace(world, pos)) {
					world.setBlockAndUpdate(pos, state);
					blacklistedPos.add(pos);
				}
			});
		}

		private boolean createBridge() {
			return create(false, editPos -> {
				final BlockPos pos = RailwayData.newBlockPos(editPos);
				final boolean isTopHalf = editPos.y - Math.floor(editPos.y) >= 0.5;
				blacklistedPos.add(getHalfPos(pos, isTopHalf));

				final BlockPos placePos;
				final BlockState placeState;
				final boolean placeHalf;

				if (isSlab && isTopHalf) {
					placePos = pos;
					placeState = state.setValue(SlabBlock.TYPE, SlabType.BOTTOM);
					placeHalf = false;
				} else {
					placePos = pos.below();
					placeState = isSlab ? state.setValue(SlabBlock.TYPE, SlabType.TOP) : state;
					placeHalf = true;
				}

				if (placePos != pos && canPlace(world, pos)) {
					world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
				}
				if (!blacklistedPos.contains(getHalfPos(placePos, placeHalf)) && canPlace(world, placePos)) {
					world.setBlockAndUpdate(placePos, placeState);
				}
			});
		}

		private boolean create(boolean includeMiddle, Consumer<Vec3> consumer) {
			final long startTime = System.currentTimeMillis();
			while (System.currentTimeMillis() - startTime < 2) {
				final Vec3 pos1 = rail.getPosition(distance);
				distance += INCREMENT;
				final Vec3 pos2 = rail.getPosition(distance);
				final Vec3 vec3 = new Vec3(pos2.x - pos1.x, 0, pos2.z - pos1.z).normalize().yRot((float) Math.PI / 2);

				for (double x = -radius; x <= radius; x += INCREMENT) {
					final Vec3 editPos = pos1.add(vec3.multiply(x, 0, x));
					final boolean wholeNumber = Math.floor(editPos.y) == Math.ceil(editPos.y);
					if (includeMiddle || Math.abs(x) > radius - INCREMENT) {
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

		private void showProgressMessage(float percentage) {
			final Player player = world.getPlayerByUUID(uuid);
			if (player != null) {
				player.displayClientMessage(Text.translatable("gui.mtr." + railActionType.progressTranslation, percentage), true);
			}
		}

		private static boolean canPlace(Level world, BlockPos pos) {
			return world.getBlockEntity(pos) == null && !(world.getBlockState(pos).getBlock() instanceof BlockNode);
		}

		private static BlockPos getHalfPos(BlockPos pos, boolean isTopHalf) {
			return RailwayData.newBlockPos(pos.getX(), pos.getY() * 2 + (isTopHalf ? 1 : 0), pos.getZ());
		}
	}

	@FunctionalInterface
	public interface RenderRail {
		void renderRail(double x1, double z1, double x2, double z2, double x3, double z3, double x4, double z4, double y1, double y2);
	}

	public enum RailActionType {
		BRIDGE("percentage_complete_bridge", "rail_action_bridge", MaterialColor.COLOR_LIGHT_GRAY),
		TUNNEL("percentage_complete_tunnel", "rail_action_tunnel", MaterialColor.COLOR_BROWN),
		TUNNEL_WALL("percentage_complete_tunnel_wall", "rail_action_tunnel_wall", MaterialColor.COLOR_GRAY);

		private final String progressTranslation;
		private final String nameTranslation;
		private final int color;

		RailActionType(String progressTranslation, String nameTranslation, MaterialColor materialColor) {
			this.progressTranslation = progressTranslation;
			this.nameTranslation = nameTranslation;
			color = materialColor.col;
		}
	}
}
