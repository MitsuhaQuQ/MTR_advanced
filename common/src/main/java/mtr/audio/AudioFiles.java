package mtr.audio;

import mtr.data.TranslatedMessage;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Checks and names announcement audio files, the same on the server and the client. A file is known by the SHA-256 of
 * its bytes, so the same recording is only stored and sent once. Only Ogg Vorbis is taken: one stream, every page whole
 * and with a correct checksum, and the three Vorbis headers at the start. The audio itself isn't decoded here.
 */
public final class AudioFiles {

	/**
	 * The most any server can allow for one file, whatever its settings, so that a file still fits in one request to the
	 * advanced editor's page.
	 */
	public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
	public static final int CHUNK_BYTES = 32 * 1024;
	/**
	 * A chunk of an upload, from a client to the server: smaller, as a packet a client sends can be at most 32767 bytes, and
	 * the request, sizes, file name and sound ID go with the chunk (the same fix as ANTE-advanced 2f3921c8).
	 */
	public static final int UPLOAD_CHUNK_BYTES = 30 * 1024;
	public static final int HASH_LENGTH = 64;
	public static final int MAX_NAME_LENGTH = 64;

	private static final int MAX_CHANNELS = 2;
	private static final int MIN_SAMPLE_RATE = 8000;
	private static final int MAX_SAMPLE_RATE = 192000;
	private static final int PAGE_HEADER_BYTES = 27;
	private static final int FLAG_CONTINUED = 0x01;
	private static final int FLAG_FIRST = 0x02;
	private static final int[] CRC_TABLE = new int[256];

	static {
		for (int i = 0; i < 256; i++) {
			int crc = i << 24;
			for (int j = 0; j < 8; j++) {
				crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04C11DB7 : crc << 1;
			}
			CRC_TABLE[i] = crc;
		}
	}

	private AudioFiles() {
	}

	public static String hash(byte[] bytes) {
		try {
			final byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
			final StringBuilder builder = new StringBuilder();
			for (final byte digestByte : digest) {
				builder.append(String.format("%02x", digestByte));
			}
			return builder.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Hashes come from the other side, and name files on disk, so only lowercase hex of the right length is a hash.
	 */
	public static boolean isHash(String text) {
		if (text == null || text.length() != HASH_LENGTH) {
			return false;
		}
		for (int i = 0; i < text.length(); i++) {
			final char c = text.charAt(i);
			if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) {
				return false;
			}
		}
		return true;
	}

	/**
	 * A file name, which announcers use to name the file ({@code <player>:<name>}): without control characters or folders,
	 * and not too long, cut before the extension so that stays. An empty name stays empty, for the caller to replace.
	 */
	public static String cleanName(String name) {
		final StringBuilder builder = new StringBuilder();
		if (name != null) {
			name.trim().codePoints().filter(codePoint -> !Character.isISOControl(codePoint)).map(codePoint -> codePoint == '/' || codePoint == '\\' ? '_' : codePoint).forEach(builder::appendCodePoint);
		}
		final String cleaned = builder.toString().trim();
		if (cleaned.codePointCount(0, cleaned.length()) <= MAX_NAME_LENGTH) {
			return cleaned;
		}
		final int dot = cleaned.lastIndexOf('.');
		final String extension = dot > 0 && cleaned.length() - dot <= 8 ? cleaned.substring(dot) : "";
		final String base = cleaned.substring(0, cleaned.length() - extension.length());
		return base.substring(0, base.offsetByCodePoints(0, MAX_NAME_LENGTH - extension.length())) + extension;
	}

	/**
	 * @return the name with {@code .ogg} added if it has no extension
	 */
	public static String withExtension(String name) {
		final int dot = name.lastIndexOf('.');
		return dot > 0 && dot < name.length() - 1 ? name : name + ".ogg";
	}

	/**
	 * @return what the file holds
	 * @throws InvalidAudioException with a reason to show to the player if it isn't a well-formed Ogg Vorbis file
	 */
	public static Info check(byte[] bytes) throws InvalidAudioException {
		if (bytes.length == 0) {
			throw new InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_empty"));
		}
		if (bytes.length > MAX_FILE_BYTES) {
			throw new InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_too_big"));
		}

		final byte[][] headers = new byte[3][];
		final ByteArrayOutputStream packet = new ByteArrayOutputStream();
		int headerCount = 0;
		int offset = 0;
		int pageNumber = 0;
		int serial = 0;
		long lastGranule = 0;

		while (offset < bytes.length) {
			if (bytes.length - offset < PAGE_HEADER_BYTES || bytes[offset] != 'O' || bytes[offset + 1] != 'g' || bytes[offset + 2] != 'g' || bytes[offset + 3] != 'S') {
				throw new InvalidAudioException(offset == 0 ? TranslatedMessage.encode("gui.mtr.audio_not_ogg") : TranslatedMessage.encode("gui.mtr.audio_trailing_data"));
			}
			if (bytes[offset + 4] != 0) {
				throw new InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_ogg_version"));
			}
			final int flags = bytes[offset + 5] & 0xFF;
			final long granule = readLong(bytes, offset + 6);
			final int pageSerial = readInt(bytes, offset + 14);
			final int sequence = readInt(bytes, offset + 18);
			final int crc = readInt(bytes, offset + 22);
			final int segmentCount = bytes[offset + 26] & 0xFF;
			final int bodyStart = offset + PAGE_HEADER_BYTES + segmentCount;
			if (bodyStart > bytes.length) {
				throw new InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_incomplete"));
			}
			int bodyLength = 0;
			for (int i = 0; i < segmentCount; i++) {
				bodyLength += bytes[offset + PAGE_HEADER_BYTES + i] & 0xFF;
			}
			final int pageEnd = bodyStart + bodyLength;
			if (pageEnd > bytes.length) {
				throw new InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_incomplete"));
			}
			if (crc(bytes, offset, pageEnd - offset) != crc) {
				throw new InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_bad_checksum"));
			}

			if (pageNumber == 0) {
				if ((flags & FLAG_FIRST) == 0) {
					throw new InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_not_ogg"));
				}
				serial = pageSerial;
			} else if (pageSerial != serial || (flags & FLAG_FIRST) != 0) {
				throw new InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_one_stream"));
			}
			if (sequence != pageNumber) {
				throw new InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_bad_page_order"));
			}
			if (granule != -1) {
				lastGranule = granule;
			}

			// The headers are the first three packets, which can span pages
			if (headerCount < 3) {
				// A page carries on a packet exactly when the last one left it unfinished
				if (((flags & FLAG_CONTINUED) != 0) != (packet.size() > 0)) {
					throw new InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_corrupt"));
				}
				int segmentStart = bodyStart;
				for (int i = 0; i < segmentCount && headerCount < 3; i++) {
					final int segmentLength = bytes[offset + PAGE_HEADER_BYTES + i] & 0xFF;
					packet.write(bytes, segmentStart, segmentLength);
					segmentStart += segmentLength;
					if (segmentLength < 255) {
						headers[headerCount] = packet.toByteArray();
						checkHeader(headers[headerCount], headerCount);
						packet.reset();
						headerCount++;
					}
				}
			}

			offset = pageEnd;
			pageNumber++;
		}

		if (headerCount < 3) {
			throw new InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_incomplete"));
		}
		final byte[] identification = headers[0];
		final int channels = identification[11] & 0xFF;
		final int sampleRate = readInt(identification, 12);
		return new Info(channels, sampleRate, lastGranule <= 0 ? 0 : lastGranule * 1000 / sampleRate);
	}

	private static void checkHeader(byte[] header, int index) throws InvalidAudioException {
		if (index == 0 && header.length >= 8 && new String(header, 0, 8, StandardCharsets.ISO_8859_1).equals("OpusHead")) {
			throw new InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_opus"));
		}
		final int type = index * 2 + 1;
		if (header.length < 7 || (header[0] & 0xFF) != type || !new String(header, 1, 6, StandardCharsets.ISO_8859_1).equals("vorbis")) {
			throw new InvalidAudioException(index == 0 ? TranslatedMessage.encode("gui.mtr.audio_vorbis_only") : TranslatedMessage.encode("gui.mtr.audio_vorbis_header_incomplete"));
		}
		if (index == 0) {
			if (header.length != 30 || readInt(header, 7) != 0 || (header[29] & 1) == 0) {
				throw new InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_vorbis_header_invalid"));
			}
			final int channels = header[11] & 0xFF;
			if (channels < 1 || channels > MAX_CHANNELS) {
				throw new InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_channels"));
			}
			final int sampleRate = readInt(header, 12);
			if (sampleRate < MIN_SAMPLE_RATE || sampleRate > MAX_SAMPLE_RATE) {
				throw new InvalidAudioException(TranslatedMessage.encode("gui.mtr.audio_sample_rate"));
			}
		}
	}

	private static int crc(byte[] bytes, int start, int length) {
		int crc = 0;
		for (int i = 0; i < length; i++) {
			// The checksum field itself counts as zeros
			final int value = i >= 22 && i < 26 ? 0 : bytes[start + i] & 0xFF;
			crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ value) & 0xFF];
		}
		return crc;
	}

	private static int readInt(byte[] bytes, int offset) {
		return (bytes[offset] & 0xFF) | (bytes[offset + 1] & 0xFF) << 8 | (bytes[offset + 2] & 0xFF) << 16 | (bytes[offset + 3] & 0xFF) << 24;
	}

	private static long readLong(byte[] bytes, int offset) {
		return (readInt(bytes, offset) & 0xFFFFFFFFL) | (long) readInt(bytes, offset + 4) << 32;
	}

	public static final class Info {

		public final int channels;
		public final int sampleRate;
		public final long durationMillis;

		private Info(int channels, int sampleRate, long durationMillis) {
			this.channels = channels;
			this.sampleRate = sampleRate;
			this.durationMillis = durationMillis;
		}
	}

	public static final class InvalidAudioException extends Exception {

		public InvalidAudioException(String message) {
			super(message);
		}
	}
}
