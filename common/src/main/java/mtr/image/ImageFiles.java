package mtr.image;

import mtr.audio.AudioFiles;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.Iterator;

/** Bounded validation for stored PNG, JPEG (0.71.0) and GIF images. */
public final class ImageFiles {

	public static final int MAX_BYTES = 5 * 1024 * 1024;
	public static final int MAX_DIMENSION = 2048;
	public static final int CHUNK = 30 * 1024;

	private ImageFiles() { }

	public static int[] check(byte[] bytes) throws Exception {
		if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("gui.mtr.image_invalid");
		if (isGif(bytes)) return AnimatedGif.check(bytes);
		if (isJpeg(bytes)) return checkJpeg(bytes);
		if (bytes.length < 33 || ByteBuffer.wrap(bytes).getLong() != 0x89504e470d0a1a0aL || ByteBuffer.wrap(bytes, 8, 4).getInt() != 13 || ByteBuffer.wrap(bytes, 12, 4).getInt() != 0x49484452) {
			throw new IllegalArgumentException("gui.mtr.image_invalid");
		}
		final int width = ByteBuffer.wrap(bytes, 16, 4).getInt(), height = ByteBuffer.wrap(bytes, 20, 4).getInt();
		checkDimensions(width, height);
		final BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
		if (image == null || image.getWidth() != width || image.getHeight() != height) throw new IllegalArgumentException("gui.mtr.image_invalid");
		image.flush();
		return new int[]{width, height};
	}

	/**
	 * A JPEG: its size read from its header first, so one too large is refused before it is decoded, then decoded whole
	 * to see that it reads (Java's decoder, as the client decodes it).
	 */
	private static int[] checkJpeg(byte[] bytes) throws Exception {
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			final Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("jpeg");
			if (!readers.hasNext()) throw new IllegalArgumentException("gui.mtr.image_invalid");
			final ImageReader reader = readers.next();
			try {
				reader.setInput(input, true, true);
				final int width = reader.getWidth(0), height = reader.getHeight(0);
				checkDimensions(width, height);
				final BufferedImage image = reader.read(0);
				if (image == null || image.getWidth() != width || image.getHeight() != height) throw new IllegalArgumentException("gui.mtr.image_invalid");
				image.flush();
				return new int[]{width, height};
			} catch (javax.imageio.IIOException e) {
				throw new IllegalArgumentException("gui.mtr.image_invalid", e);
			} finally {
				reader.dispose();
			}
		}
	}

	/**
	 * @return a JPEG's pixels as Java decodes it, for the client's texture (the game's own decoder isn't relied on for JPEG,
	 * as later game versions read only PNG)
	 */
	public static BufferedImage decodeJpeg(byte[] bytes) throws Exception {
		checkJpeg(bytes);
		final BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
		if (image == null) throw new IllegalArgumentException("gui.mtr.image_invalid");
		return image;
	}

	static void checkDimensions(int width, int height) {
		if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
			throw new IllegalArgumentException("gui.mtr.image_dimensions");
		}
	}

	public static boolean isGif(byte[] bytes) {
		return bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F'
				&& bytes[3] == '8' && (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a';
	}

	public static boolean isJpeg(byte[] bytes) {
		return bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
	}

	/** The file name extension an image's bytes are stored under */
	public static String extensionOf(byte[] bytes) {
		return isGif(bytes) ? ".gif" : isJpeg(bytes) ? ".jpg" : ".png";
	}

	/** The content type an image's bytes are served with */
	public static String contentTypeOf(byte[] bytes) {
		return isGif(bytes) ? "image/gif" : isJpeg(bytes) ? "image/jpeg" : "image/png";
	}

	public static String hash(byte[] bytes) { return AudioFiles.hash(bytes); }
	public static boolean isHash(String hash) { return AudioFiles.isHash(hash); }
	public static boolean isReference(String reference) { return reference != null && reference.length() <= 129 && reference.matches("[a-z0-9_.-]+:[^:/\\\\\\p{Cntrl}]+\\.(png|gif|jpg)"); }
}
