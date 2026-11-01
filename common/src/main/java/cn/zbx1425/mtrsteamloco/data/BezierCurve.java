package cn.zbx1425.mtrsteamloco.data;

import org.msgpack.core.MessagePacker;
import net.minecraft.network.FriendlyByteBuf;
import org.msgpack.value.*;

import java.util.*;
import java.io.IOException;

public class BezierCurve {

    public static void main(String[] args) {
        long nano = System.nanoTime();
        BezierCurve curve = new BezierCurve(
            0.1,
            10000, 0, -1,
            0, 0, 0,
            15, 20, 0,
            -1, 0, -50000
        );
        nano = System.nanoTime() - nano;
        Runtime.getRuntime().gc();
        pl("Elapsed time", "gen " + nano / 1e9 + 's');
        pl(curve.toString());
        printMemUsage();
        // while (true);
    }

    public static void pl(Object... args) {
        System.out.println(Arrays.toString(args));
    }

    public static void printMemUsage() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        pl("Memory usage", "total " + totalMemory / 1024 / 1024 + "MB", "used " + usedMemory / 1024 / 1024 + "MB", "free " + freeMemory / 1024 / 1024 + "MB");
    }


    private static final int STEP = 3;
    private double epsilon = 1e-1;
    private double length = 0;
    private List<Vec3> points = new ArrayList<>();
    private List<Double> TMapping = new ArrayList<>(), SMapping = new ArrayList<>();
    private Vec3 max = new Vec3(0, 0, 0), min = new Vec3(0, 0, 0);

    public BezierCurve(BezierCurve curve) {
        this.epsilon = curve.epsilon;
        this.points = new ArrayList<>(curve.points);
        this.TMapping = new ArrayList<>(curve.TMapping);
        this.SMapping = new ArrayList<>(curve.SMapping);
        this.max = new Vec3(curve.max);
        this.min = new Vec3(curve.min);
    }

    public BezierCurve(double epsilon, List<Vec3> points) {
        this.epsilon = epsilon;
        this.points = points;
        mapping();
    }

    public BezierCurve(double epsilon, net.minecraft.world.phys.Vec3... ctrls) {
        this.epsilon = epsilon;
        for (net.minecraft.world.phys.Vec3 ctrl : ctrls) {
            points.add(new Vec3(ctrl.x, ctrl.y, ctrl.z));
        }
        mapping();
    }

    public BezierCurve(double epsilon, double... ctrls) {
        if (ctrls.length % 3 != 0) {
            throw new IllegalArgumentException("Invalid number of control points");
        }
        for (int i = 0; i < ctrls.length; i += 3) {
            points.add(new Vec3(ctrls[i], ctrls[i+1], ctrls[i+2]));
        }
        mapping();        
    }

    public BezierCurve(FriendlyByteBuf buf) {
        int size = buf.readInt();
        points = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            points.add(new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()));
        }
        epsilon = buf.readDouble();
        mapping();
    }

    public BezierCurve(Map<String, Value> map) {
        Value value = map.get("bezier_curve");
        if (value instanceof ArrayValue av) {
            double[] cache = new double[2];
            int index = 0;
            for (Value v : av) {
                if (v instanceof NumberValue nv) {
                    if (index >= 2) {
                        index = 0;
                        points.add(new Vec3(cache[0], cache[1], nv.toDouble()));
                    } else cache[index++] = nv.toDouble();
                } else {
                    throw new IllegalArgumentException("Invalid control point");
                }
            }
            if (index == 1) {
                epsilon = cache[0];
            }
        } else {
            points.add(new Vec3(0, 0, 0));
        };
        mapping();
    }

    public void save(MessagePacker messagePacker) throws IOException{
        messagePacker.packString("bezier_curve");
        messagePacker.packArrayHeader(points.size() * 3 + 1);
        for (Vec3 point : points) {
            messagePacker.packDouble(point.x);
            messagePacker.packDouble(point.y);
            messagePacker.packDouble(point.z);
        }
        messagePacker.packDouble(epsilon);
    }

    public void save(FriendlyByteBuf buf) {
        buf.writeInt(points.size());
        for (Vec3 point : points) {
            buf.writeDouble(point.x);
            buf.writeDouble(point.y);
            buf.writeDouble(point.z);
        }
        buf.writeDouble(epsilon);
    }

    private void mapping() { // F1

        Queue<Double> startQueue = new ArrayDeque<>();
        Queue<Double> endQueue = new ArrayDeque<>();
        List<Double> tList = new ArrayList<>();
        startQueue.add(0.0);
        endQueue.add(1.0);

        while (!startQueue.isEmpty() && !endQueue.isEmpty()) {
            double tStart = startQueue.poll();
            double tEnd = endQueue.poll();

            Vec3 start = bezier(tStart);
            Vec3 end = bezier(tEnd);
            double target = start.distanceTo(end) / STEP;
            double tDiff = (tEnd - tStart) / STEP;
            
            double t = tStart;
            Vec3 prev = bezier(t);
            for (int i = 0; i < STEP; i++) {
                t += tDiff;
                Vec3 curr = bezier(t);
                double dist = prev.distanceTo(curr);
                if (Math.abs(dist - target) < epsilon) {
                    tList.add(t);
                } else {
                    startQueue.add(t - tDiff);
                    endQueue.add(t);
                }
                prev = curr;
            }
        }

        tList.add(0.0);
        tList.sort(Double::compare);
        Vec3 prev = bezier(0);
        TMapping.clear();
        SMapping.clear();
        for (Double t : tList) {
            Vec3 curr = bezier(t);
            max = max.max(curr);
            min = min.min(curr);
            TMapping.add(t);
            length += prev.distanceTo(curr);
            SMapping.add(length);
            prev = curr;
        }
    }


    private Vec3 bezier(double t) {
        if (points.size() == 4) {
            // 三次贝塞尔曲线的优化计算
            Vec3 p0 = points.get(0);
            Vec3 p1 = points.get(1);
            Vec3 p2 = points.get(2);
            Vec3 p3 = points.get(3);
            double omt = 1.0 - t;
            double omt2 = omt * omt;
            double omt3 = omt2 * omt;
            double t2 = t * t;
            double t3 = t2 * t;
            return new Vec3(
                omt3 * p0.x + 3 * omt2 * t * p1.x + 3 * omt * t2 * p2.x + t3 * p3.x,
                omt3 * p0.y + 3 * omt2 * t * p1.y + 3 * omt * t2 * p2.y + t3 * p3.y,
                omt3 * p0.z + 3 * omt2 * t * p1.z + 3 * omt * t2 * p2.z + t3 * p3.z
            );
        } else {
            Vec3[] tmp = points.toArray(new Vec3[0]); // 预分配复用数组
            for (int k = 1; k < tmp.length; k++) {
                for (int i = 0; i < tmp.length - k; i++) {
                    tmp[i] = tmp[i].lerpTo(tmp[i + 1], t);
                }
            }
            return tmp[0];
        }
    }
    
    public List<Vec3> getPoints() {
        return points;
    }

    public Vec3 getPosition(double arcT) {
        if (arcT <= 0) return bezier(0);
        if (arcT > length) return bezier(1);
        for (int i = 0; i < SMapping.size() - 1; i++) {
            if (arcT >= SMapping.get(i) && arcT < SMapping.get(i + 1)) {
                double t = TMapping.get(i) + (arcT - SMapping.get(i)) / (SMapping.get(i + 1) - SMapping.get(i)) * (TMapping.get(i + 1) - TMapping.get(i));
                return bezier(t);
            }
        }
        return bezier(1);
    }

    public double getLength() {
        return length;
    }

    public boolean isBetween(double x, double y, double z, double radius) {
        Vec3 min = this.min.subtract(new Vec3(radius, radius, radius));
        Vec3 max = this.max.add(new Vec3(radius, radius, radius));
        return x >= min.x && x <= max.x && y >= min.y && y <= max.y && z >= min.z && z <= max.z;
    }

    public void setEpsilon(double epsilon) {
        this.epsilon = epsilon;
        mapping();
    }

    public BezierCurve copy() {
        return new BezierCurve(this);
    }

    public BezierCurve getTransposition() {
        List<Vec3> newPoints = new ArrayList<>(points);
        Collections.reverse(newPoints);
        return new BezierCurve(epsilon, newPoints);
    }

    public String toString() {
        return String.format("BezierCurve{length=%.2f, MappingSize=%d}", length, TMapping.size());
    }

    public static class Vec3 {
        public double x, y, z;

        public Vec3(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Vec3(Vec3 other) {
            this.x = other.x;
            this.y = other.y;
            this.z = other.z;
        }

        public Vec3 add(Vec3 other) {
            return new Vec3(x + other.x, y + other.y, z + other.z);
        }

        public Vec3 subtract(Vec3 other) {
            return new Vec3(x - other.x, y - other.y, z - other.z);
        }

        public Vec3 scale(double scalar) {
            return new Vec3(x * scalar, y * scalar, z * scalar);
        }

        public double length() {
            return Math.sqrt(x * x + y * y + z * z);
        }

        public Vec3 cross(Vec3 other) {
            return new Vec3(
                y * other.z - z * other.y,
                z * other.x - x * other.z,
                x * other.y - y * other.x
            );
        }

        public double distanceTo(Vec3 other) {
            return Math.sqrt(distanceToSquared(other));
        }

        public double distanceToSquared(Vec3 other) {
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }

        public double dot(Vec3 other) {
            return x * other.x + y * other.y + z * other.z;
        }

        public Vec3 lerpTo(Vec3 other, double t) {
            return add(other.subtract(this).scale(t));
        }

        public Vec3 max(Vec3 other) {
            return new Vec3(Math.max(x, other.x), Math.max(y, other.y), Math.max(z, other.z));
        }

        public Vec3 min(Vec3 other) {
            return new Vec3(Math.min(x, other.x), Math.min(y, other.y), Math.min(z, other.z));
        }

        public String toString() {
            return String.format("Vec3(%.16f %.16f %.16f)", x, y, z);
        }
    }
}