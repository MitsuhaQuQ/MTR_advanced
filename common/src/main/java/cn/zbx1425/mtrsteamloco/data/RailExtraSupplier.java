package cn.zbx1425.mtrsteamloco.data;

import net.minecraft.core.BlockPos;
import mtr.data.Rail;
import mtr.data.RailType;
import net.minecraft.util.Mth;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;

public interface RailExtraSupplier {

    // "": default, "null": hidden
    String getModelKey();

    void setModelKey(String key);

    boolean getRenderReversed();

    void setRenderReversed(boolean value);

    float getVerticalCurveRadius();

    void setVerticalCurveRadius(float value);

    int getHeight();

    Map<Double, Float> getRollAngleMap();

    void setRollAngleMap(Map<Double, Float> rollAngleMap);

    Map<String, String> getCustomConfigs();
    
    void setCustomConfigs(Map<String, String> customConfigs);

    Map<String, ConfigResponder> getCustomResponders();

    void setCustomResponders(Map<String, ConfigResponder> customResponders);

    void setOpeningDirection(int direction);

    void setOpeningDirectionRaw(int direction);

    int getOpeningDirection();

    int getOpeningDirectionRaw();

    void setRailType(RailType railType);

    void partialCopyFrom(Rail rail);
    
    void setRollingOffset(float rollingOffset);

    float getRollingOffset();

    boolean isStraightOnly();

    boolean isBetween(double x, double y, double z, double radius);

    Rail getTransposition(RailType railType);

    void sendUpdateC2S();

    BlockPos getPosStart();

    BlockPos getPosEnd();

    /**
     * Which way a positive roll of {@code Rail.getCantRoll} (the heading growing, from +x towards +z) turns the track here,
     * so the outer rail is the higher one
     */
    float CANT_ROLL_SIGN = 1F;

    static float getVTheta(Rail rail, double verticalCurveRadius) {
        double H = Math.abs(((RailExtraSupplier) rail).getHeight());
        double L = rail.getLength();
        double R = verticalCurveRadius;
        return 2 * (float) Mth.atan2(Math.sqrt(H * H - 4 * R * H + L * L) - L, H - 4 * R);
    }

    /**
     * @return how far the roll at a distance along the rail lifts it (as {@code RailMixin} adds it to the height), so a
     * grade check can leave it out (0.98.0)
     */
    static double getRollLift(Rail rail, double value) {
        // Without the mixins (unit tests) rails have no roll
        if (!((Object) rail instanceof RailExtraSupplier)) {
            return 0;
        }
        final float roll = getRollAngle(rail, value);
        return roll == 0 ? 0 : Math.abs(((RailExtraSupplier) rail).getRollingOffset() * Math.sin(roll));
    }

    static float getRollAngle(Rail rail, double value) {
        // A rail with superelevation set on its nodes (0.98.0) leans by it, along the rail's own way, rather than by the
        // roll angles set by hand: Rail.getCantRoll, as the map read below would be seen from a rail rendered reversed
        if (rail.hasCant()) {
            return CANT_ROLL_SIGN * rail.getCantRoll(value);
        }
        Map<Double, Float> rollAngleMap = ((RailExtraSupplier) rail).getRollAngleMap();
        boolean reversed = ((RailExtraSupplier) rail).getRenderReversed();
        float k = reversed ? -1F : 1F;
        
        if (reversed) {
            value = rail.getLength() - value;
        }
        
        if (rollAngleMap.isEmpty()) {
            return 0;
        }
        
        List<Double> keys = new ArrayList<>(rollAngleMap.keySet());
        keys.sort(Double::compareTo);
        
        if (value <= keys.get(0)) {
            return k * rollAngleMap.get(keys.get(0));
        }
        
        int size = keys.size();
        if (value >= keys.get(size - 1)) {
            return k * rollAngleMap.get(keys.get(size - 1));
        }
        
        double lastKey = keys.get(0);
        for (int i = 1; i < size; i++) {
            double currentKey = keys.get(i);
            if (lastKey <= value && value < currentKey) {
                double t = (value - lastKey) / (currentKey - lastKey);
                double cosFactor = (1 - Math.cos(t * Math.PI)) / 2.0;
                float interpolated = (float) (rollAngleMap.get(lastKey) * (1 - cosFactor) 
                                            + rollAngleMap.get(currentKey) * cosFactor);
                return k * interpolated;
            }
            lastKey = currentKey;
        }
        return 0;
    }
}
