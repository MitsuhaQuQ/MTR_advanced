package mtr.audio;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AnnouncementSoundTest {

	@Test
	void namesAddedFiles() {
		final Set<String> mods = new HashSet<>(Arrays.asList("minecraft", "mtr"));
		assertEquals("steve", AnnouncementSound.getNamespace("Steve", mods));
		// A player named like a mod gets a namespace of their own, so the mod's sounds still play
		assertEquals("mtr_player", AnnouncementSound.getNamespace("MTR", mods));
		assertEquals("steve:下一站.ogg", AnnouncementSound.getUploadedString("steve", "下一站.ogg"));
		assertEquals("steve:next.ogg", AnnouncementSound.getNameKey("steve", "Next.OGG"));
		assertArrayEquals(new String[]{"steve", "Next.ogg"}, AnnouncementSound.splitUploaded("Steve:Next.ogg"));
		// Only the first colon splits, so a file name can have one
		assertArrayEquals(new String[]{"steve", "a:b.ogg"}, AnnouncementSound.splitUploaded("steve:a:b.ogg"));
		assertNull(AnnouncementSound.splitUploaded("steve:"));
		assertNull(AnnouncementSound.splitUploaded(":next.ogg"));
		assertNull(AnnouncementSound.splitUploaded("next.ogg"));
		assertNull(AnnouncementSound.splitUploaded("https://example.com/a.ogg"));
	}

	@Test
	void tellsTheKindsApart() {
		// Added files and sounds look alike; the client looks for an added file first and the sound is what's left
		assertEquals("mtr:ticket_barrier", AnnouncementSound.getSoundId("mtr:ticket_barrier").toString());
		assertArrayEquals(new String[]{"mtr", "ticket_barrier"}, AnnouncementSound.splitUploaded("mtr:ticket_barrier"));
		assertNull(AnnouncementSound.getSoundId("steve:下一站.ogg"));

		assertTrue(AnnouncementSound.isNetwork("https://example.com/a.wav"));
		assertTrue(AnnouncementSound.isNetwork("HTTP://example.com/a.ogg"));
		assertNull(AnnouncementSound.getSoundId("https://example.com/a.wav"));
		assertFalse(AnnouncementSound.isNetwork("ftp://example.com/a.wav"));
	}

	@Test
	void invalidTextPlaysNothing() {
		// Saves from other versions can hold anything, and none of it may break loading the block
		assertNull(AnnouncementSound.getSoundId("Not A Sound!"));
		assertNull(AnnouncementSound.getSoundId(""));
		assertEquals("", AnnouncementSound.clean(null));
		assertEquals("mtr:a", AnnouncementSound.clean("  mtr:a "));
		assertEquals("", AnnouncementSound.clean(new String(new char[AnnouncementSound.MAX_LENGTH + 1]).replace('\0', 'a')));
	}
}
