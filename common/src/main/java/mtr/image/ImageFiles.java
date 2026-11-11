package mtr.image;

import mtr.audio.AudioFiles;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;

/** Bounded validation for stored PNG and GIF images. */
public final class ImageFiles {

	public static final int MAX_BYTES = 5 * 1024 * 1024;
	public static final int MAX_DIMENSION = 2048;
	public static final int CHUNK = 30 * 1024;

	private ImageFiles() { }

	public static int[] check(byte[] bytes) throws Exception {
		if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("gui.mtr.image_invalid");
		if (isGif(bytes)) return AnimatedGif.check(bytes);
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

	static void checkDimensions(int width, int height) {
		if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
			throw new IllegalArgumentException("gui.mtr.image_dimensions");
		}
	}

	public static boolean isGif(byte[] bytes) {
		return bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F'
				&& bytes[3] == '8' && (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a';
	}

	public static String hash(byte[] bytes) { return AudioFiles.hash(bytes); }
	public static boolean isHash(String hash) { return AudioFiles.isHash(hash); }
	public static boolean isReference(String reference) { return reference != null && reference.length() <= 129 && reference.matches("[a-z0-9_.-]+:[^:/\\\\\\p{Cntrl}]+\\.(png|gif)"); }
}
