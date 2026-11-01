package cn.zbx1425.mtrsteamloco.data;

import mtr.data.RailAngle;

public interface RailAngleExtra {

    static RailAngle fromDegrees(double degrees) {
        return RailAngle.fromAngle(degrees);
    }

    static RailAngle fromRadians(double radians) {
        return RailAngle.fromAngle(Math.toDegrees(radians));
    }
}
