package mtr.image;

import mtr.media.MediaPermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ImagesTest {
	@Test void acceptsImagesLargerThanTwoMiBAndRejectsMoreThanFiveMiB() throws Exception {
		BufferedImage image = new BufferedImage(1024,1024,BufferedImage.TYPE_INT_ARGB);
		java.util.Random random = new java.util.Random(32);
		for(int y=0;y<1024;y++) for(int x=0;x<1024;x++) image.setRGB(x,y,random.nextInt());
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ImageIO.write(image,"png",bytes);
		assertTrue(bytes.size()>2*1024*1024);
		assertTrue(bytes.size()<=5*1024*1024);
		assertArrayEquals(new int[]{1024,1024},ImageFiles.check(bytes.toByteArray()));
		assertThrows(IllegalArgumentException.class,()->ImageFiles.check(new byte[5*1024*1024+1]));
	}
	/** 0.71.0: a JPEG is kept as JPEG, its size checked from its header, its name ending in .jpg */
	@Test void acceptsJpegAsItIs() throws Exception {
		final BufferedImage image = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < 200; y++) for (int x = 0; x < 300; x++) image.setRGB(x, y, x * 0x010203 + y);
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", bytes);
		final byte[] jpeg = bytes.toByteArray();
		assertTrue(ImageFiles.isJpeg(jpeg));
		assertArrayEquals(new int[]{300, 200}, ImageFiles.check(jpeg));
		assertEquals(".jpg", ImageFiles.extensionOf(jpeg));
		assertEquals("image/jpeg", ImageFiles.contentTypeOf(jpeg));
		assertEquals(300, ImageFiles.decodeJpeg(jpeg).getWidth());
		assertTrue(ImageFiles.isReference("steve:photo.jpg"));
		assertFalse(ImageFiles.isReference("steve:photo.jpeg"));
		// Too wide is refused from the header, before decoding; a broken one doesn't read
		final ByteArrayOutputStream wide = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(2049, 1, BufferedImage.TYPE_INT_RGB), "jpg", wide);
		final IllegalArgumentException tooWide = assertThrows(IllegalArgumentException.class, () -> ImageFiles.check(wide.toByteArray()));
		assertEquals("gui.mtr.image_dimensions", tooWide.getMessage());
		assertThrows(IllegalArgumentException.class, () -> ImageFiles.check(java.util.Arrays.copyOf(jpeg, 40)));
		// Stored under .jpg only
		try (ImageDatabase database = new ImageDatabase(folder.resolve("jpeg.db"))) {
			assertThrows(IllegalArgumentException.class, () -> database.add("steve", "photo.png", jpeg, "uuid-steve", "Steve"));
			database.add("steve", "photo.jpg", jpeg, "uuid-steve", "Steve");
			assertEquals("steve:photo.jpg", database.entries().get(0).name);
		}
	}
	@TempDir Path folder;
	private byte[] png(int color) throws Exception {
		final BufferedImage image = new BufferedImage(8, 4, BufferedImage.TYPE_INT_ARGB); image.setRGB(0, 0, color);
		final ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); return out.toByteArray();
	}
	@Test void validatesPngDimensionsBeforeDecoding() throws Exception {
		assertArrayEquals(new int[]{8, 4}, ImageFiles.check(png(0xFF112233)));
		assertThrows(Exception.class, () -> ImageFiles.check(new byte[]{1, 2, 3}));
		final byte[] bytes = png(0); ByteBuffer.wrap(bytes, 16, 4).putInt(100000);
		assertThrows(Exception.class, () -> ImageFiles.check(bytes));
	}
	@Test void namesAreInternalAndNeverUrlsOrPaths() {
		assertTrue(ImageFiles.isReference("steve:车站图.png"));
		assertFalse(ImageFiles.isReference("https://example.com/map.png"));
		assertFalse(ImageFiles.isReference("file:///tmp/map.png"));
		assertFalse(ImageFiles.isReference("steve:../map.png"));
		assertFalse(ImageFiles.isReference("steve:folder\\map.png"));
	}
	@Test void settingsRoundTripRejectsInvalidNumbersAndUrls() {
		final PaintingSettings settings = new PaintingSettings(); settings.image = "steve:map.png"; settings.width = 3.5; settings.x = -0.25; settings.z = 0.125;
		final PaintingSettings copy = PaintingSettings.parse(settings.json()); assertEquals(settings.width, copy.width); assertEquals(settings.image, copy.image); assertEquals(-0.25, copy.x);
		settings.width = Double.NaN; assertThrows(IllegalArgumentException.class, settings::json);
		assertThrows(IllegalArgumentException.class, () -> PaintingSettings.parse("{\"image\":\"https://example.com/a.png\"}"));
		assertThrows(IllegalArgumentException.class, () -> PaintingSettings.parse("{\"width\":17}"));
		assertThrows(IllegalArgumentException.class, () -> PaintingSettings.parse("{\"x\":17}"));
	}
	@Test void namesUpdateAndBytesDeduplicateAndSurviveReopen() throws Exception {
		final Path path = folder.resolve("pictures.db"); final byte[] first = png(0xFF112233), second = png(0xFF445566);
		try (ImageDatabase database = new ImageDatabase(path)) {
			database.add("steve", "map.png", first, "uuid-steve", "Steve"); database.add("alex", "map.png", first, "uuid-alex", "Alex"); assertEquals(2, database.entries().size());
			database.delete("STEVE:MAP.PNG"); assertArrayEquals(first, database.read(ImageFiles.hash(first)));
			database.add("alex", "MAP.png", second, "uuid-alex", "Alex"); assertEquals(1, database.entries().size()); assertNull(database.read(ImageFiles.hash(first))); assertEquals("uuid-alex", database.entries().get(0).uploader);
		}
		try (ImageDatabase database = new ImageDatabase(path)) { assertEquals(1, database.entries().size()); assertArrayEquals(second, database.read(ImageFiles.hash(second))); database.delete("alex:map.png"); assertNull(database.read(ImageFiles.hash(second))); }
	}
	@Test void administratorsDeleteOtherUsersResourcesAndOwnersKeepTheirRight() {
		assertTrue(MediaPermissions.canDelete("alex", "steve", true));
		assertFalse(MediaPermissions.canDelete("alex", "steve", false));
		assertTrue(MediaPermissions.canDelete("steve", "steve", false));
		assertFalse(MediaPermissions.canDelete("alex", null, false));
	}
	@Test void clientCacheKeepsContentWithoutAnyNames() throws Exception {
		final Path path = folder.resolve("cache/pictures.db"); final byte[] bytes = png(0xFF112233); final String hash = ImageFiles.hash(bytes);
		try (ImageDatabase database = new ImageDatabase(path)) { database.writeData(hash, bytes); assertTrue(database.entries().isEmpty()); assertThrows(IllegalArgumentException.class, () -> database.writeData("bad", bytes)); }
		try (ImageDatabase database = new ImageDatabase(path)) { assertArrayEquals(bytes, database.read(hash)); database.deleteData(hash); assertNull(database.read(hash)); }
	}
}
