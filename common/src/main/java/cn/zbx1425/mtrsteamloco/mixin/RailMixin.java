package cn.zbx1425.mtrsteamloco.mixin;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.msgpack.core.MessagePacker;
import org.msgpack.value.Value;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import cn.zbx1425.mtrsteamloco.Main;
import cn.zbx1425.mtrsteamloco.data.ConfigResponder;
import cn.zbx1425.mtrsteamloco.data.RailAngleExtra;
import cn.zbx1425.mtrsteamloco.data.RailCalculator;
import cn.zbx1425.mtrsteamloco.data.RailCalculator.Vec2;
import cn.zbx1425.mtrsteamloco.data.RailExtraSupplier;
import cn.zbx1425.mtrsteamloco.network.util.DoubleFloatMapSerializer;
import cn.zbx1425.mtrsteamloco.network.util.StringMapSerializer;
import cn.zbx1425.mtrsteamloco.render.rail.BakedRail;
import cn.zbx1425.sowcer.math.Matrix4f;
import cn.zbx1425.sowcer.math.Vector3f;
import cn.zbx1425.mtrsteamloco.network.PacketUpdateRail;
import io.netty.buffer.Unpooled;
import mtr.data.MessagePackHelper;
import mtr.data.Rail;
import mtr.data.Rail.RenderRail;
import mtr.data.RailAngle;
import mtr.data.RailType;
import mtr.data.TransportMode;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

@Mixin(value = Rail.class, priority = 1425)
public abstract class RailMixin implements RailExtraSupplier {

    private static final double ACCEPT_THRESHOLD = 1E-4;
    private final mtr.data.RailGeometryCache mtrGeometryCache = new mtr.data.RailGeometryCache();
    @Shadow(remap = false) @Final @Mutable private RailType railType;
	@Shadow(remap = false) @Final @Mutable private TransportMode transportMode;
	@Shadow(remap = false) @Final @Mutable private RailAngle facingStart;
	@Shadow(remap = false) @Final @Mutable private RailAngle facingEnd;
	@Shadow(remap = false) @Final @Mutable private double h1, k1, r1, tStart1, tEnd1;
	@Shadow(remap = false) @Final @Mutable private double h2, k2, r2, tStart2, tEnd2;
	@Shadow(remap = false) @Final @Mutable private int yStart, yEnd;
	@Shadow(remap = false) @Final @Mutable private boolean reverseT1, isStraight1, reverseT2, isStraight2;

    private String modelKey = "";
    private boolean isSecondaryDir = false;
    private float verticalCurveRadius = 0f;
    private Map<String, String> customConfigs = new HashMap<>();
    private Map<String, ConfigResponder> customResponders = new HashMap<>();
    private Map<Double, Float> rollAngleMap = new HashMap<>();
    private int openingDirection = 0;// 0 不开 1 左 2 右 3 双向
    private float rollingOffset = 1.435F / 2F;
    private BlockPos posStart, posEnd;
    private RailAngle railAngleStart, railAngleEnd;

    @Override
    public String getModelKey() {
        return modelKey;
    }

    @Override
    public void setModelKey(String key) {
        this.modelKey = key;
    }

    @Override
    public boolean getRenderReversed() {
        return isSecondaryDir;
    }

    @Override
    public void setRenderReversed(boolean value) {
        this.isSecondaryDir = value;
    }

    @Override
    public float getVerticalCurveRadius() {
        return verticalCurveRadius;
    }

    @Override
    public void setVerticalCurveRadius(float value) {
        this.verticalCurveRadius = value;
    }

    @Override
    public int getHeight() {
        return yEnd - yStart;
    }

    @Override
    public Map<Double, Float> getRollAngleMap() {
        return rollAngleMap;
    }

    @Override
    public void setRollAngleMap(Map<Double, Float> rollAngleMap) {
        this.rollAngleMap = rollAngleMap;
    }

    @Override
    public Map<String, String> getCustomConfigs() {
        return customConfigs;
    }

    @Override
    public void setCustomConfigs(Map<String, String> customConfigs) {
        this.customConfigs = customConfigs;
    }

    @Override
    public Map<String, ConfigResponder> getCustomResponders() {
        return customResponders;
    }

    @Override
    public void setCustomResponders(Map<String, ConfigResponder> customResponders) {
        this.customResponders = customResponders;
    }

    @Override
    public void setOpeningDirectionRaw(int direction) {
        this.openingDirection = direction;
    }

    @Override
    public void setOpeningDirection(int direction) {
        if (getRenderReversed()) {
            if (direction == 1) direction = 2;
            else if (direction == 2) direction = 1;
        }
        this.openingDirection = direction;
    }

    @Override
    public int getOpeningDirection() {
        int direction = openingDirection;
        if (getRenderReversed()) {
            if (direction == 1) direction = 2;
            else if (direction == 2) direction = 1;
        }
        return direction;
    }

    @Override
    public int getOpeningDirectionRaw() {
        return openingDirection;
    }

    @Override
    public void setRailType(RailType railType) {
        this.railType = railType;
    }

    @Override
    public void partialCopyFrom(Rail other) {
        RailExtraSupplier oth = (RailExtraSupplier) other;
        setModelKey(oth.getModelKey());
        // setRenderReversed(oth.getRenderReversed());
        setVerticalCurveRadius(oth.getVerticalCurveRadius());
        setCustomConfigs(oth.getCustomConfigs());
        double length0 = other.getLength();
        double length1 = ((Rail) (Object) this).getLength();
        double k = length1 / length0;
        Map<Double, Float> src = new HashMap<>(oth.getRollAngleMap());
        Map<Double, Float> dst = new HashMap<>();
        for (Map.Entry<Double, Float> entry : src.entrySet()) {
            dst.put(entry.getKey() * k, entry.getValue());
        }
        setRollAngleMap(dst);
        setOpeningDirection(oth.getOpeningDirection());
        setRollingOffset(oth.getRollingOffset());
    }

    @Override
    public void setRollingOffset(float rollingOffset) {
        this.rollingOffset = rollingOffset;
    }

    @Override
    public float getRollingOffset() {
        return rollingOffset;
    }

    @Override
    public boolean isStraightOnly() {
        return (isStraight1 || (h1 == 0 && k1 == 0 && r1 == 0 && tStart1 == 0 && tEnd1 == 0)) && (isStraight2 || (h2 == 0 && k2 == 0 && r2 == 0 && tStart2 == 0 && tEnd2 == 0));
    }

    @Override
    public boolean isBetween(double x, double y, double z, double radius) {
        Vec3 start = getPosition(0);
        Vec3 end = getPosition(getLength());
        Vec3 max = max(start, end).add(radius, radius, radius);
        Vec3 min = min(start, end).add(-radius, -radius, -radius);
        return x >= min.x && x <= max.x && y >= min.y && y <= max.y && z >= min.z && z <= max.z;
    }

    @Override
    public Rail getTransposition(RailType railType) {
        if (railType != RailType.NONE) railType = this.railType;
        Rail result = new Rail(posEnd, facingEnd, posStart, facingStart, railType, transportMode);
        RailExtraSupplier extra = (RailExtraSupplier) (Object) result;
        extra.setModelKey(modelKey);
        extra.setRenderReversed(!isSecondaryDir);
        extra.setVerticalCurveRadius(verticalCurveRadius);
        extra.setCustomConfigs(customConfigs);
        extra.setRollAngleMap(rollAngleMap);
        extra.setOpeningDirection(openingDirection);
        extra.setRollingOffset(rollingOffset);
        return result;
    }

    @Override
    public void sendUpdateC2S() {
        PacketUpdateRail.sendUpdateC2S((Rail) (Object) this, posStart, posEnd);
    }

    @Override
    public BlockPos getPosStart() {
        return posStart;
    }

    @Override
    public BlockPos getPosEnd() {
        return posEnd;
    }

    private Vec3 max(Vec3 a, Vec3 b) {
        return new Vec3(Math.max(a.x, b.x), Math.max(a.y, b.y), Math.max(a.z, b.z));
    }

    private Vec3 min(Vec3 a, Vec3 b) {
        return new Vec3(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z));
    }

    @Inject(method = "<init>(Lnet/minecraft/core/BlockPos;Lmtr/data/RailAngle;Lnet/minecraft/core/BlockPos;Lmtr/data/RailAngle;Lmtr/data/RailType;Lmtr/data/TransportMode;)V", at = @At("TAIL"))
    private void onCreate(BlockPos posStart, RailAngle facingStart, BlockPos posEnd, RailAngle facingEnd, RailType railType, TransportMode transportMode, CallbackInfo ci) {
        this.posStart = posStart;
        this.posEnd = posEnd;
    }

    private BlockPos round(Vec3 pos) {
        return new BlockPos((int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z));
    }

    @Shadow(remap = false) public abstract double getLength();
    @Shadow(remap = false) public abstract Vec3 getPosition(double distance);
    @Shadow(remap = false) public abstract RailAngle getRailAngle(boolean getEnd);

    @Inject(method = "<init>(Ljava/util/Map;)V", at = @At("TAIL"), remap = false)
    private void fromMessagePack(Map<String, Value> map, CallbackInfo ci) {
        MessagePackHelper messagePackHelper = new MessagePackHelper(map);
        modelKey = messagePackHelper.getString("model_key", "");
        isSecondaryDir = messagePackHelper.getBoolean("is_secondary_dir", false);
        verticalCurveRadius = messagePackHelper.getFloat("vertical_curve_radius", 0);
        try {
			customConfigs = StringMapSerializer.deserialize(messagePackHelper.getString("custom_configs"));
		} catch (IOException e) {
			customConfigs = new HashMap<>();
		}
        try {
            rollAngleMap = DoubleFloatMapSerializer.deserialize(messagePackHelper.getString("roll_angle_map"));
        } catch (Exception e) {
            rollAngleMap = new HashMap<>();
        }
        openingDirection = messagePackHelper.getInt("opening_direction", 0);
        rollingOffset = messagePackHelper.getFloat("rolling_offset", 1.435F / 2F);
        // A rail ANTE saved as a Bézier curve is made MTR's own shape when read (Rail), so only its ends are needed here
        if (!map.containsKey("pos_start") || !map.containsKey("pos_end")) {
            posStart = round(getPosition(0));
            posEnd = round(getPosition(getLength()));
            return;
        }
        posStart = BlockPos.of(messagePackHelper.getLong("pos_start"));
        posEnd = BlockPos.of(messagePackHelper.getLong("pos_end"));
    }

    @Inject(method = "toMessagePack", at = @At("TAIL"), remap = false)
    private void toMessagePack(MessagePacker messagePacker, CallbackInfo ci) throws IOException {
        messagePacker.packString("model_key").packString(modelKey);
        messagePacker.packString("is_secondary_dir").packBoolean(isSecondaryDir);
        messagePacker.packString("vertical_curve_radius").packFloat(verticalCurveRadius);
        String res;
		try {
			res = StringMapSerializer.serializeToString(customConfigs);
		} catch (IOException e) {
			res = "";
		}
		messagePacker.packString("custom_configs").packString(res);
        String res2;
        try {
			res2 = DoubleFloatMapSerializer.serializeToString(rollAngleMap);
		} catch (Exception e) {
			res2 = "";
		}
        messagePacker.packString("roll_angle_map").packString(res2);
        messagePacker.packString("opening_direction").packInt(openingDirection);
        messagePacker.packString("rolling_offset").packFloat(rollingOffset);
        // Every rail is MTR's own shape now; written as before so the save keeps its keys
        messagePacker.packString("path_mode").packInt(0);
        messagePacker.packString("pos_start").packLong(posStart.asLong());
        messagePacker.packString("facing_start").packDouble(facingStart.angleRadians);
        messagePacker.packString("pos_end").packLong(posEnd.asLong());
        messagePacker.packString("facing_end").packDouble(facingEnd.angleRadians);
    }

    @Inject(method = "messagePackLength", at = @At("TAIL"), cancellable = true, remap = false)
    private void messagePackLength(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(cir.getReturnValue() + 12);
    }

    private final int NTE_PACKET_EXTRA_MAGIC = 0x25141425;

    @Inject(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("TAIL"))
    private void fromPacket(FriendlyByteBuf packet, CallbackInfo ci) {
        if (!Main.enableRegistry) return;
        if (packet.readableBytes() <= 4) return;
        if (packet.readInt() != NTE_PACKET_EXTRA_MAGIC) {
            packet.readerIndex(packet.readerIndex() - 4);
            return;
        }
        modelKey = packet.readUtf();
        isSecondaryDir = packet.readBoolean();
        verticalCurveRadius = packet.readFloat();
        try {
			customConfigs = StringMapSerializer.deserialize(packet.readUtf());
		} catch (IOException e) {
			customConfigs = new HashMap<>();
		}
        try {
            rollAngleMap = DoubleFloatMapSerializer.deserialize(packet.readUtf());
        } catch (Exception e) {
            rollAngleMap = new HashMap<>();
        }
        openingDirection = packet.readInt();
        rollingOffset = packet.readFloat();
        // The path mode, always 0 now, and the ends with their angles, which the rail's own shape gives already
        packet.readInt();
        posStart = packet.readBlockPos();
        packet.readDouble();
        posEnd = packet.readBlockPos();
        packet.readDouble();
    }

    @Inject(method = "writePacket", at = @At("TAIL"))
    private void toPacket(FriendlyByteBuf packet, CallbackInfo ci) {
        if (!Main.enableRegistry) return;
        packet.writeInt(NTE_PACKET_EXTRA_MAGIC);
        packet.writeUtf(modelKey);
        packet.writeBoolean(isSecondaryDir);
        packet.writeFloat(verticalCurveRadius);
        String res;
		try {
			res = StringMapSerializer.serializeToString(customConfigs);
		} catch (IOException e) {
			res = "";
		}
		packet.writeUtf(res);
        String res2;
        try {
			res2 = DoubleFloatMapSerializer.serializeToString(rollAngleMap);
        } catch (Exception e) {
            res2 = "";
        }
        packet.writeUtf(res2);
        packet.writeInt(openingDirection);
        packet.writeFloat(rollingOffset);
        packet.writeInt(0);
        packet.writeBlockPos(posStart);
        packet.writeDouble(facingStart.angleRadians);
        packet.writeBlockPos(posEnd);
        packet.writeDouble(facingEnd.angleRadians);
    }

    // @Redirect(method = "renderSegment", remap = false, at = @At(value = "INVOKE", target = "Ljava/lang/Math;round(D)J"))
    // private long redirectRenderSegmentRound(double r) {
    //     if (ClientConfig.getRailRenderLevel() < 2) return Math.round(r);

    //     Rail instance = (Rail)(Object)this;
    //     if (instance.railType == RailType.NONE) {
    //         return Math.round(r);
    //     } else {
    //         return Math.round(r / RailModelRegistry.getProperty(RailRenderDispatcher.getModelKeyForRender(instance)).repeatInterval);
    //     }
    // }

    private float vTheta;

    private static final int CABLE_CURVATURE_SCALE = 1000;
	private static final int MAX_CABLE_DIP = 8;

    private double _getPositionY(double value) {
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

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void render(RenderRail callback, float offsetRadius1, float offsetRadius2, CallbackInfo ci) {
        final Rail rail = (Rail) (Object) this;
        final double count = rail.getLength();
        if (!(count > 0) || !Double.isFinite(count)) { ci.cancel(); return; }
        if (count > 8192) {
            final double increment = count / Math.round(count);
            final Vector3f a = new Vector3f(offsetRadius1, 0, 0), b = new Vector3f(offsetRadius2, 0, 0);
            Matrix4f mat0 = getLookAtMat(rail, 0);
            for (double i = 0; i < count - 0.1; i += increment) {
                final Matrix4f mat1 = getLookAtMat(rail, i + increment);
                final Vector3f c1 = mat0.transform(a), c2 = mat0.transform(b), c3 = mat1.transform(b), c4 = mat1.transform(a);
                callback.renderRail(c1.x(), c1.z(), c2.x(), c2.z(), c3.x(), c3.z(), c4.x(), c4.z(),
                        mat0.transform(Vector3f.ZERO).y(), mat1.transform(Vector3f.ZERO).y());
                mat0 = mat1;
            }
            ci.cancel();
            return;
        }
        final double[] key = {h1, k1, r1, tStart1, tEnd1, h2, k2, r2, tStart2, tEnd2,
                yStart, yEnd, reverseT1 ? 1 : 0, reverseT2 ? 1 : 0, isStraight1 ? 1 : 0, isStraight2 ? 1 : 0,
                verticalCurveRadius, rollingOffset, isSecondaryDir ? 1 : 0, railType.ordinal(), count, offsetRadius1, offsetRadius2};
        final float[] points = mtrGeometryCache.get(key, rollAngleMap, () -> {
            final double increment = count / Math.round(count);
            final int capacity = (int) Math.ceil(count) + 1;
            final float[] data = new float[capacity * 10];
            final Vector3f a = new Vector3f(offsetRadius1, 0, 0);
            final Vector3f b = new Vector3f(offsetRadius2, 0, 0);
            int index = 0;
            Matrix4f mat0 = getLookAtMat(rail, 0);
            for (double i = 0; i < count - 0.1; i += increment) {
                final Matrix4f mat1 = getLookAtMat(rail, i + increment);
                final Vector3f c1 = mat0.transform(a), c2 = mat0.transform(b);
                final Vector3f c3 = mat1.transform(b), c4 = mat1.transform(a);
                data[index++] = c1.x(); data[index++] = c1.z();
                data[index++] = c2.x(); data[index++] = c2.z();
                data[index++] = c3.x(); data[index++] = c3.z();
                data[index++] = c4.x(); data[index++] = c4.z();
                data[index++] = mat0.transform(Vector3f.ZERO).y();
                data[index++] = mat1.transform(Vector3f.ZERO).y();
                mat0 = mat1;
            }
            return Arrays.copyOf(data, index);
        });
        for (int i = 0; i < points.length; i += 10) {
            callback.renderRail(points[i], points[i+1], points[i+2], points[i+3], points[i+4],
                    points[i+5], points[i+6], points[i+7], points[i+8], points[i+9]);
        }
        ci.cancel();
	}

    private static final double HALF_ACCEPT_THRESHOLD = ACCEPT_THRESHOLD / 2;

    private Matrix4f getLookAtMat(Rail rail, double value) {
        if (value < HALF_ACCEPT_THRESHOLD) value = HALF_ACCEPT_THRESHOLD;
        else if (value + HALF_ACCEPT_THRESHOLD > rail.getLength()) value = rail.getLength() - HALF_ACCEPT_THRESHOLD;
        Vec3 from = rail.getPosition(value - HALF_ACCEPT_THRESHOLD);
        Vec3 to = rail.getPosition(value + HALF_ACCEPT_THRESHOLD);
        Vec3 mid = from.add(to).scale(0.5F);
        float roll = RailExtraSupplier.getRollAngle(rail, value);
        return BakedRail.getLookAtMat(mid, from, to, roll, 0, isSecondaryDir);
    }

    @Inject(method = "getPositionY", at = @At("HEAD"), cancellable = true, remap = false)
    private void onGetPositionY(double rawValue, CallbackInfoReturnable<Double> cir) {
        if (((Rail)(Object)this).railType.railSlopeStyle == RailType.RailSlopeStyle.CABLE) return;
        double H = Math.abs(yEnd - yStart);
        double L = ((Rail)(Object)this).getLength();
        int sign = yStart < yEnd ? 1 : -1;
        double maxRadius = (H == 0) ? 0 : Math.abs((H * H + L * L) / (H * 4));
        double result = 0d;
        if (verticalCurveRadius < 0) {
            // Magic value for a flat rail
            result = sign * (rawValue / L) * H + yStart;
        } else if (verticalCurveRadius == 0 || verticalCurveRadius > maxRadius) {
            // Magic default value / impossible radius, fallback to MTR all curvy track
            result  = _getPositionY(rawValue);
        } else {
            if (vTheta == 0) vTheta = RailExtraSupplier.getVTheta((Rail)(Object)this, verticalCurveRadius);
            if (!Double.isFinite(vTheta)) {
                result = _getPositionY(rawValue);
            } else {
                float curveL = Mth.sin(vTheta) * verticalCurveRadius;
                float curveH = (1 - Mth.cos(vTheta)) * verticalCurveRadius;
                if (rawValue < curveL) {
                    float r = (float)rawValue;
                    result = sign * (verticalCurveRadius - Math.sqrt(verticalCurveRadius * verticalCurveRadius - r * r)) + yStart;
                } else if (rawValue > L - curveL) {
                    float r = (float)(L - rawValue);
                    result = -sign * (verticalCurveRadius - Math.sqrt(verticalCurveRadius * verticalCurveRadius - r * r)) + yEnd;
                } else {
                    result = sign * (((rawValue - curveL) / (L - 2 * curveL)) * (H - 2 * curveH) + curveH) + yStart;
                }
            }
        }
        result += rollingYOffset(rawValue);
        cir.setReturnValue(result);
        cir.cancel();
        return;
    }

    private double rollingYOffset(double rawValue) {
        float r = RailExtraSupplier.getRollAngle((Rail) (Object) this, rawValue);
        float offset = getRollingOffset();
        if (r == 0) return 0;
        double dy = new Vec2(offset, 0).rotateRad(r).z;
        return Math.abs(dy);
    }

    // private double 

    private static final FriendlyByteBuf hashBuilder = new FriendlyByteBuf(Unpooled.buffer());
    private byte[] dataBytes;
    private int hashCode;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (dataBytes == null) createDataBytes();
        if (((RailMixin)o).dataBytes == null) ((RailMixin)o).createDataBytes();
        return Arrays.equals(dataBytes, ((RailMixin)o).dataBytes);
    }

    @Override
    public int hashCode() {
        if (dataBytes == null) createDataBytes();
        return hashCode;
    }

    private void createDataBytes() {
        hashBuilder.clear();
        ((Rail)(Object)this).writePacket(hashBuilder);
        dataBytes = new byte[hashBuilder.writerIndex()];
        hashBuilder.getBytes(0, dataBytes);
        hashCode = Arrays.hashCode(dataBytes);
    }
}
