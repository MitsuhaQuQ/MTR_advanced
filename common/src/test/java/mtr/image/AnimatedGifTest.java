package mtr.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class AnimatedGifTest {
	// Four frames on a 2×1 canvas, with offset frames and both disposal modes.
	private static byte[] sample() {
		final String hex = "47494638396102000100810000000000ff000000ff000000ff21ff0b4e45545343415045322e30030101000021f90405050000002c00000000020001000002024c0a0021f9040d070000002c010000000100010000020254010021f90409030000002c00000000010001000002025c010021f904050a0000002c01000000010001000002025401003b";
		final byte[] bytes = new byte[hex.length() / 2];
		for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
		return bytes;
	}

	@Test
	void composesOffsetFramesAndRespectsDisposalAndTiming() throws Exception {
		final byte[] bytes = sample();
		assertArrayEquals(new int[]{2, 1}, ImageFiles.check(bytes));
		final AnimatedGif gif = AnimatedGif.decode(bytes);
		assertArrayEquals(new int[]{0xFFFF0000, 0xFFFF0000}, gif.pixels(gif.frameAt(49)));
		assertArrayEquals(new int[]{0xFFFF0000, 0xFF00FF00}, gif.pixels(gif.frameAt(50)));
		assertArrayEquals(new int[]{0xFF0000FF, 0xFFFF0000}, gif.pixels(gif.frameAt(120)));
		assertArrayEquals(new int[]{0, 0xFF00FF00}, gif.pixels(gif.frameAt(150)));
		assertEquals(0, gif.frameAt(250));
		assertEquals(3, gif.frameAt(500));
	}

	@Test
	void validatesGifLimitsBeforeComposingAndAcceptsInternalGifReferences() {
		final byte[] bytes = sample();
		assertTrue(ImageFiles.isReference("steve:动画.gif"));
		assertFalse(ImageFiles.isReference("https://example.com/image.gif"));
		assertThrows(Exception.class, () -> ImageFiles.check(Arrays.copyOf(bytes, bytes.length - 1)));
		bytes[6] = 1;
		bytes[7] = 8;
		assertThrows(IllegalArgumentException.class, () -> ImageFiles.check(bytes));
	}

	@Test
	void preservesTheAnimationInPicturesDatabase(@TempDir Path folder) throws Exception {
		final Path path = folder.resolve("pictures.db");
		final byte[] bytes = sample();
		try (ImageDatabase database = new ImageDatabase(path)) {
			database.add("steve", "animation.gif", bytes, "uuid-steve", "Steve");
		}
		try (ImageDatabase database = new ImageDatabase(path)) {
			final ImageDatabase.Entry entry = database.entries().get(0);
			assertEquals("steve:animation.gif", entry.name);
			assertEquals(2, entry.width);
			assertEquals(1, entry.height);
			assertArrayEquals(bytes, database.read(entry.hash));
		}
	}
}
