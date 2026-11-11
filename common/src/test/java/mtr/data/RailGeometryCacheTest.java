package mtr.data;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RailGeometryCacheTest {
	@Test void reusesGeometryButInvalidatesWidthShapeAndInPlaceRollEdits() {
		RailGeometryCache cache = new RailGeometryCache();
		Map<Double, Float> rolls = new HashMap<>();
		double[] key = {1, 2, 3};
		AtomicInteger builds = new AtomicInteger();
		java.util.function.Supplier<float[]> build = () -> new float[]{builds.incrementAndGet()};
		float[] first = cache.get(key, rolls, build);
		assertSame(first, cache.get(key.clone(), rolls, build));
		key[0] = 4;
		assertNotSame(first, cache.get(key, rolls, build));
		rolls.put(0D, 0.5F);
		cache.get(key, rolls, build);
		assertEquals(3, builds.get());
		cache.get(key, rolls, build);
		assertEquals(3, builds.get());
	}
	@Test void oversizedGeometryIsNotRetained() {
		RailGeometryCache cache = new RailGeometryCache();
		AtomicInteger builds = new AtomicInteger();
		for (int i = 0; i < 2; i++) cache.get(new double[]{1}, new HashMap<>(), () -> { builds.incrementAndGet(); return new float[81921]; });
		assertEquals(2, builds.get());
	}
}
