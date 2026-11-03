package transitsim.io;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Named values as bytes, for the component's own data sets (S4e): each value is its name, then whole numbers, kept as
 * arrays of longs, which is all the named values the plans write need.
 */
public final class BinaryKeyed {

	private BinaryKeyed() {
	}

	public static void writeString(BinaryWriter writer, String text) {
		final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		writer.writeVarInt(bytes.length);
		for (final byte value : bytes) {
			writer.writeByte(value);
		}
	}

	public static String readString(BinaryReader reader) {
		final byte[] bytes = new byte[reader.readVarInt()];
		for (int i = 0; i < bytes.length; i++) {
			bytes[i] = reader.readByte();
		}
		return new String(bytes, StandardCharsets.UTF_8);
	}

	/**
	 * Collects named values, to be written with {@link #writeTo}.
	 */
	public static final class Writer implements KeyedWriter {

		private final Map<String, Object> values = new TreeMap<>();

		@Override
		public void writeString(String key, String value) {
			values.put(key, value);
		}

		@Override
		public void writeInt(String key, int value) {
			values.put(key, new long[]{value});
		}

		@Override
		public void writeLong(String key, long value) {
			values.put(key, new long[]{value});
		}

		@Override
		public void writeBoolean(String key, boolean value) {
			values.put(key, new long[]{value ? 1 : 0});
		}

		@Override
		public void writeInts(String key, int[] ints) {
			final long[] longs = new long[ints.length];
			for (int i = 0; i < ints.length; i++) {
				longs[i] = ints[i];
			}
			values.put(key, new long[][]{longs});
		}

		@Override
		public void writeLongs(String key, long[] longs) {
			values.put(key, new long[][]{longs.clone()});
		}

		@Override
		public void writeLongArrays(String key, List<long[]> arrays) {
			values.put(key, arrays.toArray(new long[0][]));
		}

		public void writeTo(BinaryWriter writer) {
			writer.writeVarInt(values.size());
			values.forEach((key, value) -> {
				BinaryKeyed.writeString(writer, key);
				if (value instanceof String) {
					writer.writeByte(0);
					BinaryKeyed.writeString(writer, (String) value);
				} else if (value instanceof long[]) {
					writer.writeByte(1);
					writer.writeLong(((long[]) value)[0]);
				} else {
					final long[][] arrays = (long[][]) value;
					writer.writeByte(2);
					writer.writeVarInt(arrays.length);
					for (final long[] array : arrays) {
						writer.writeVarInt(array.length);
						for (final long element : array) {
							writer.writeLong(element);
						}
					}
				}
			});
		}
	}

	/**
	 * Reads back what a {@link Writer} wrote.
	 */
	public static final class Reader implements KeyedReader {

		private final Map<String, Object> values = new TreeMap<>();

		public Reader(BinaryReader reader) {
			final int count = reader.readVarInt();
			for (int i = 0; i < count; i++) {
				final String key = BinaryKeyed.readString(reader);
				final int type = reader.readByte();
				if (type == 0) {
					values.put(key, BinaryKeyed.readString(reader));
				} else if (type == 1) {
					values.put(key, reader.readLong());
				} else {
					final long[][] arrays = new long[reader.readVarInt()][];
					for (int j = 0; j < arrays.length; j++) {
						arrays[j] = new long[reader.readVarInt()];
						for (int k = 0; k < arrays[j].length; k++) {
							arrays[j][k] = reader.readLong();
						}
					}
					values.put(key, arrays);
				}
			}
		}

		@Override
		public String getString(String key, String defaultValue) {
			final Object value = values.get(key);
			return value instanceof String ? (String) value : defaultValue;
		}

		@Override
		public int getInt(String key, int defaultValue) {
			final Object value = values.get(key);
			return value instanceof Long ? (int) (long) (Long) value : defaultValue;
		}

		@Override
		public long getLong(String key, long defaultValue) {
			final Object value = values.get(key);
			return value instanceof Long ? (Long) value : defaultValue;
		}

		@Override
		public boolean getBoolean(String key, boolean defaultValue) {
			final Object value = values.get(key);
			return value instanceof Long ? (Long) value != 0 : defaultValue;
		}

		@Override
		public long[] getLongs(String key) {
			final Object value = values.get(key);
			return value instanceof long[][] && ((long[][]) value).length > 0 ? ((long[][]) value)[0].clone() : new long[0];
		}

		@Override
		public List<long[]> getLongArrays(String key) {
			final Object value = values.get(key);
			if (!(value instanceof long[][])) {
				return Collections.emptyList();
			}
			final List<long[]> arrays = new ArrayList<>();
			for (final long[] array : (long[][]) value) {
				arrays.add(array.clone());
			}
			return arrays;
		}
	}
}
