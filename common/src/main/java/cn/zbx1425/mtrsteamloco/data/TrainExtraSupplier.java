package cn.zbx1425.mtrsteamloco.data;

import mtr.data.Rail;
import mtr.data.Train;

import java.util.Map;

public interface TrainExtraSupplier {
    
    Map<String, String> getCustomConfigs();

    void setCustomConfigs(Map<String, String> customConfigs);

    boolean isConfigsChanged();
    
    void isConfigsChanged(boolean isConfigsChanged);

    Map<String, ConfigResponder> getConfigResponders();

    void setConfigResponders(Map<String, ConfigResponder> configResponders);

    float getRollAngleAt(double rv);

    static float getRollAngleAt(Train train, int car) {
        // double p = train.getRailProgress() - car * train.spacing - train.spacing / 2;
        // return ((TrainExtraSupplier) train).getRollAngleAt(p);
        boolean b = train.isReversed();
        int c1 = (b ? train.trainCars - car : car);
        car += 1;
        int c2 = (b ? train.trainCars - car : car);
        double p = train.getRailProgress();
        double p1 = p - c1 * train.spacing;
        double p2 = p - c2 * train.spacing;
        TrainExtraSupplier supplier = (TrainExtraSupplier) train;
        return (supplier.getRollAngleAt(p1) + supplier.getRollAngleAt(p2)) / 2;
    }
}