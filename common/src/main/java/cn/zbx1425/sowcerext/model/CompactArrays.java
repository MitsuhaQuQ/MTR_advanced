package cn.zbx1425.sowcerext.model;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lets meshes with the same geometry share one array, which most repaints of a train are. The arrays are never changed
 * once they are here. Only the lookup is cleared at the end of a reload, and the arrays stay with the meshes using them.
 */
public final class CompactArrays {

	private static final Map<Key, float[]> FLOATS = new ConcurrentHashMap<>();
	private static final Map<Key, int[]> INTS = new ConcurrentHashMap<>();
	private static final AtomicLong SAVED_BYTES = new AtomicLong();

	private CompactArrays() {
	}

	public static float[] share(float[] array) {
		final float[] shared = FLOATS.putIfAbsent(new Key(array, Arrays.hashCode(array)), array);
		if (shared == null) {
			return array;
		}
		SAVED_BYTES.addAndGet((long) array.length * Float.BYTES);
		return shared;
	}

	public static int[] share(int[] array) {
		final int[] shared = INTS.putIfAbsent(new Key(array, Arrays.hashCode(array)), array);
		if (shared == null) {
			return array;
		}
		SAVED_BYTES.addAndGet((long) array.length * Integer.BYTES);
		return shared;
	}

	/**
	 * @return the bytes saved since the last time this was called
	 */
	public static long clear() {
		FLOATS.clear();
		INTS.clear();
		return SAVED_BYTES.getAndSet(0);
	}

	private static final class Key {

		private final Object array;
		private final int hash;

		private Key(Object array, int hash) {
			this.array = array;
			this.hash = hash;
		}

		@Override
		public boolean equals(Object object) {
			if (!(object instanceof Key)) {
				return false;
			}
			final Object other = ((Key) object).array;
			if (array instanceof float[]) {
				return other instanceof float[] && Arrays.equals((float[]) array, (float[]) other);
			}
			return other instanceof int[] && Arrays.equals((int[]) array, (int[]) other);
		}

		@Override
		public int hashCode() {
			return hash;
		}
	}
}
