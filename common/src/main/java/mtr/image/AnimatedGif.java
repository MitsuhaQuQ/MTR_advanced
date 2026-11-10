package mtr.image;

import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Composited GIF frames in logical-screen coordinates. */
public final class AnimatedGif {
	public static final int MAX_FRAMES = 256;
	public static final int MAX_PIXELS = 16 * 1024 * 1024;

	public final int width, height;
	private final int[][] frames;
	private final long[] frameEnds;
	private final int repeats;

	private AnimatedGif(int width, int height, int[][] frames, long[] frameEnds, int repeats) {
		this.width = width;
		this.height = height;
		this.frames = frames;
		this.frameEnds = frameEnds;
		this.repeats = repeats;
	}

	public int pixelCount() {
		return width * height * frames.length;
	}

	public int[] pixels(int frame) {
		return frames[frame];
	}

	public int frameAt(long elapsedMillis) {
		final long duration = frameEnds[frameEnds.length - 1];
		final long elapsed = Math.max(0, elapsedMillis);
		if (repeats >= 0 && elapsed / duration > repeats) return frameEnds.length - 1;
		final long position = elapsed % duration;
		for (int i = 0; i < frameEnds.length; i++) {
			if (position < frameEnds[i]) return i;
		}
		return frameEnds.length - 1;
	}

	public static AnimatedGif decode(byte[] bytes) throws IOException {
		return read(bytes, true);
	}

	static int[] check(byte[] bytes) throws IOException {
		final AnimatedGif gif = read(bytes, false);
		return new int[]{gif.width, gif.height};
	}

	private static AnimatedGif read(byte[] bytes, boolean compose) throws IOException {
		if (bytes.length < 14 || bytes.length > ImageFiles.MAX_BYTES || !ImageFiles.isGif(bytes) || bytes[bytes.length - 1] != 0x3B) {
			throw new IllegalArgumentException("gui.mtr.image_invalid");
		}
		final ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
		try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
			reader.setInput(input);
			final Node stream = reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0");
			final Node screen = child(stream, "LogicalScreenDescriptor");
			final int width = number(screen, "logicalScreenWidth"), height = number(screen, "logicalScreenHeight");
			ImageFiles.checkDimensions(width, height);
			final int count = reader.getNumImages(true);
			if (count < 1) throw new IllegalArgumentException("gui.mtr.image_invalid");
			if (count > MAX_FRAMES || (long) width * height * count > MAX_PIXELS) {
				throw new IllegalArgumentException("gui.mtr.image_animation_limit");
			}

			final int[][] frames = compose ? new int[count][] : null;
			final long[] ends = compose ? new long[count] : null;
			final BufferedImage canvas = compose ? new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB) : null;
			final Graphics2D graphics = compose ? canvas.createGraphics() : null;
			int repeats = 0;
			long end = 0;
			try {
				for (int i = 0; i < count; i++) {
					final Node metadata = reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
					final Node descriptor = child(metadata, "ImageDescriptor");
					final int left = number(descriptor, "imageLeftPosition"), top = number(descriptor, "imageTopPosition");
					final int w = reader.getWidth(i), h = reader.getHeight(i);
					if (w < 1 || h < 1 || left < 0 || top < 0 || left + w > width || top + h > height) {
						throw new IllegalArgumentException("gui.mtr.image_invalid");
					}
					final BufferedImage image = reader.read(i);
					if (!compose) {
						image.flush();
						continue;
					}

					final Node control = child(metadata, "GraphicControlExtension");
					final boolean transparent = "TRUE".equals(attribute(control, "transparentColorFlag"));
					final int background = transparent ? 0 : backgroundColor(stream);
					final String disposal = attribute(control, "disposalMethod");
					if (i == 0) {
						repeats = repeats(metadata);
						fill(graphics, background, 0, 0, width, height);
					}
					final int[] previous = "restoreToPrevious".equals(disposal) ? canvas.getRGB(left, top, w, h, null, 0, w) : null;
					graphics.drawImage(image, left, top, null);
					image.flush();
					frames[i] = canvas.getRGB(0, 0, width, height, null, 0, width);
					final int delay = number(control, "delayTime");
					end += delay < 2 ? 100 : delay * 10L;
					ends[i] = end;

					if (previous != null) canvas.setRGB(left, top, w, h, previous, 0, w);
					else if ("restoreToBackgroundColor".equals(disposal)) fill(graphics, background, left, top, w, h);
				}
			} finally {
				if (graphics != null) graphics.dispose();
			}
			return new AnimatedGif(width, height, frames, ends, repeats);
		} finally {
			reader.dispose();
		}
	}

	private static int repeats(Node metadata) {
		final Node extensions = child(metadata, "ApplicationExtensions");
		if (extensions != null) {
			for (Node extension = extensions.getFirstChild(); extension != null; extension = extension.getNextSibling()) {
				final String id = attribute(extension, "applicationID") + attribute(extension, "authenticationCode");
				final Object payload = ((IIOMetadataNode) extension).getUserObject();
				if (("NETSCAPE2.0".equals(id) || "ANIMEXTS1.0".equals(id)) && payload instanceof byte[]) {
					final byte[] data = (byte[]) payload;
					if (data.length >= 3 && data[0] == 1) {
						final int loops = (data[1] & 255) | ((data[2] & 255) << 8);
						return loops == 0 ? -1 : loops;
					}
				}
			}
		}
		return 0;
	}

	private static int backgroundColor(Node stream) {
		final Node table = child(stream, "GlobalColorTable");
		final int index = number(table, "backgroundColorIndex");
		if (table != null) {
			for (Node entry = table.getFirstChild(); entry != null; entry = entry.getNextSibling()) {
				if (number(entry, "index") == index) {
					return 0xFF000000 | (number(entry, "red") << 16) | (number(entry, "green") << 8) | number(entry, "blue");
				}
			}
		}
		return 0;
	}

	private static void fill(Graphics2D graphics, int color, int x, int y, int width, int height) {
		graphics.setComposite(AlphaComposite.Src);
		graphics.setColor(new Color(color, true));
		graphics.fillRect(x, y, width, height);
		graphics.setComposite(AlphaComposite.SrcOver);
	}

	private static Node child(Node parent, String name) {
		if (parent != null) {
			for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
				if (name.equals(node.getNodeName())) return node;
			}
		}
		return null;
	}

	private static String attribute(Node node, String name) {
		final Node attribute = node == null ? null : node.getAttributes().getNamedItem(name);
		return attribute == null ? "" : attribute.getNodeValue();
	}

	private static int number(Node node, String name) {
		final String value = attribute(node, name);
		return value.isEmpty() ? 0 : Integer.parseInt(value);
	}
}
