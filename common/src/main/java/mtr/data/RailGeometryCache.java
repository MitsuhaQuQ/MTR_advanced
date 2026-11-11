package mtr.data;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Per-rail, single-width cache. Never retains lighting, cameras or world references. */
public final class RailGeometryCache {
	private double[] key;
	private Map<Double, Float> rolls;
	private float[] points;

	public float[] get(double[] geometry, Map<Double, Float> currentRolls, Supplier<float[]> build) {
		if (points == null || !Arrays.equals(key, geometry) || !currentRolls.equals(rolls)) {
			final float[] result = build.get();
			// Bound retained memory for pathological long rails; ordinary rails contain ten floats per segment.
			if (result.length <= 81920) {
				key = geometry.clone();
				rolls = new HashMap<>(currentRolls);
				points = result;
			} else {
				key = null;
				rolls = null;
				points = null;
			}
			return result;
		}
		return points;
	}
}
