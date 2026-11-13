package mtr.image;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PaintingProtectionTest {

	private static PaintingImage painting(String settings) {
		return new PaintingImage() {
			@Override
			public String mtr$getImageSettings() {
				return settings;
			}

			@Override
			public void mtr$setImageSettings(String newSettings) {
			}
		};
	}

	@Test
	void onlyPaintingsShowingAPictureAreKeptFromKill() {
		final Object picture = painting("{\"image\":\"abc:photo.png\"}");
		final Object plain = painting("");
		// Its picture cleared with the brush
		final Object cleared = painting("{\"image\":\"\"}");
		assertFalse(PaintingProtection.isProtected(cleared));
		assertFalse(PaintingProtection.isProtected(painting("not json")));
		final Object other = "an entity";
		assertTrue(PaintingProtection.isProtected(picture));
		assertFalse(PaintingProtection.isProtected(plain));
		assertFalse(PaintingProtection.isProtected(other));
		assertFalse(PaintingProtection.isProtected(null));

		final List<Object> targets = Arrays.asList(plain, picture, other);
		assertEquals(Arrays.asList(plain, other), PaintingProtection.withoutProtected(targets));
		// Nothing to leave out: the same collection
		final Collection<Object> none = Arrays.asList(plain, other);
		assertSame(none, PaintingProtection.withoutProtected(none));
	}
}
