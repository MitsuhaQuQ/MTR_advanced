package mtr.advanced;

import mtr.data.MessagePackHelper;
import org.msgpack.core.MessagePacker;
import transitsim.io.KeyedReader;
import transitsim.io.KeyedWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The component's named values, saved in the host's msgpack maps.
 */
public final class MessagePackKeyed {

	private MessagePackKeyed() {
	}

	public static class Reader implements KeyedReader {

		private final MessagePackHelper messagePackHelper;

		public Reader(MessagePackHelper messagePackHelper) {
			this.messagePackHelper = messagePackHelper;
		}

		@Override
		public String getString(String key, String defaultValue) {
			return messagePackHelper.getString(key, defaultValue);
		}

		@Override
		public int getInt(String key, int defaultValue) {
			return messagePackHelper.getInt(key, defaultValue);
		}

		@Override
		public long getLong(String key, long defaultValue) {
			return messagePackHelper.getLong(key, defaultValue);
		}

		@Override
		public boolean getBoolean(String key, boolean defaultValue) {
			return messagePackHelper.getBoolean(key, defaultValue);
		}

		@Override
		public long[] getLongs(String key) {
			final List<Long> values = new ArrayList<>();
			messagePackHelper.iterateArrayValue(key, value -> values.add(value.asIntegerValue().asLong()));
			return values.stream().mapToLong(Long::longValue).toArray();
		}

		@Override
		public List<long[]> getLongArrays(String key) {
			final List<long[]> arrays = new ArrayList<>();
			messagePackHelper.iterateArrayValue(key, value -> {
				final List<Long> values = new ArrayList<>();
				value.asArrayValue().forEach(innerValue -> values.add(innerValue.asIntegerValue().asLong()));
				arrays.add(values.stream().mapToLong(Long::longValue).toArray());
			});
			return arrays;
		}
	}

	public static class Writer implements KeyedWriter {

		private final MessagePacker messagePacker;

		public Writer(MessagePacker messagePacker) {
			this.messagePacker = messagePacker;
		}

		@Override
		public void writeString(String key, String value) throws IOException {
			messagePacker.packString(key).packString(value);
		}

		@Override
		public void writeInt(String key, int value) throws IOException {
			messagePacker.packString(key).packInt(value);
		}

		@Override
		public void writeLong(String key, long value) throws IOException {
			messagePacker.packString(key).packLong(value);
		}

		@Override
		public void writeBoolean(String key, boolean value) throws IOException {
			messagePacker.packString(key).packBoolean(value);
		}

		@Override
		public void writeInts(String key, int[] values) throws IOException {
			messagePacker.packString(key).packArrayHeader(values.length);
			for (final int value : values) {
				messagePacker.packInt(value);
			}
		}

		@Override
		public void writeLongs(String key, long[] values) throws IOException {
			messagePacker.packString(key).packArrayHeader(values.length);
			for (final long value : values) {
				messagePacker.packLong(value);
			}
		}

		@Override
		public void writeLongArrays(String key, List<long[]> values) throws IOException {
			messagePacker.packString(key).packArrayHeader(values.size());
			for (final long[] array : values) {
				messagePacker.packArrayHeader(array.length);
				for (final long value : array) {
					messagePacker.packLong(value);
				}
			}
		}
	}
}
