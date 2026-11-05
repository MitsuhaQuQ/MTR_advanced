package transitsim.io;

import java.io.ByteArrayOutputStream;

/**
 * A {@link BinaryWriter} into a byte array and a {@link BinaryReader} from one, for data the component saves as bytes.
 */
public final class ByteArrayBinary {

	private static final int VAR_INT_MAX_BYTES = 5;

	private ByteArrayBinary() {
	}

	public static final class Writer implements BinaryWriter {

		private final ByteArrayOutputStream stream = new ByteArrayOutputStream();

		@Override
		public void writeByte(int value) {
			stream.write(value);
		}

		@Override
		public void writeBoolean(boolean value) {
			stream.write(value ? 1 : 0);
		}

		@Override
		public void writeInt(int value) {
			for (int shift = 24; shift >= 0; shift -= 8) {
				stream.write(value >>> shift);
			}
		}

		@Override
		public void writeLong(long value) {
			for (int shift = 56; shift >= 0; shift -= 8) {
				stream.write((int) (value >>> shift));
			}
		}

		@Override
		public void writeVarInt(int value) {
			int rest = value;
			while ((rest & ~0x7F) != 0) {
				stream.write(rest & 0x7F | 0x80);
				rest >>>= 7;
			}
			stream.write(rest);
		}

		public byte[] toBytes() {
			return stream.toByteArray();
		}
	}

	public static final class Reader implements BinaryReader {

		private final byte[] bytes;
		private int position;

		public Reader(byte[] bytes) {
			this.bytes = bytes;
		}

		@Override
		public boolean hasMore() {
			return position < bytes.length;
		}

		@Override
		public byte readByte() {
			if (position >= bytes.length) {
				throw new IllegalStateException("Read past the end of the data");
			}
			return bytes[position++];
		}

		@Override
		public int readUnsignedByte() {
			return readByte() & 0xFF;
		}

		@Override
		public boolean readBoolean() {
			return readByte() != 0;
		}

		@Override
		public int readInt() {
			int value = 0;
			for (int i = 0; i < 4; i++) {
				value = value << 8 | readUnsignedByte();
			}
			return value;
		}

		@Override
		public long readLong() {
			long value = 0;
			for (int i = 0; i < 8; i++) {
				value = value << 8 | readUnsignedByte();
			}
			return value;
		}

		@Override
		public int readVarInt() {
			int value = 0;
			for (int i = 0; i < VAR_INT_MAX_BYTES; i++) {
				final int part = readUnsignedByte();
				value |= (part & 0x7F) << i * 7;
				if ((part & 0x80) == 0) {
					return value;
				}
			}
			throw new IllegalStateException("Var-int too long");
		}
	}
}
