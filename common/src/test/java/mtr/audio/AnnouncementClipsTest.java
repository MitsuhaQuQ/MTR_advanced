package mtr.audio;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An announcer's sounds one after another (0.72.0): any sound an announcer takes, with the gap after it, kept as text.
 */
class AnnouncementClipsTest {

	@Test
	void soundsAndGapsReadBackAndBadTextReadsAsNone() {
		final List<AnnouncementClips.Clip> clips = Arrays.asList(new AnnouncementClips.Clip("mtr:ticket_barrier", 15),
				new AnnouncementClips.Clip("steve:站名.ogg", 0), new AnnouncementClips.Clip("  ", 5), new AnnouncementClips.Clip("https://example.org/a.ogg", 99999));
		final List<AnnouncementClips.Clip> read = AnnouncementClips.parse(AnnouncementClips.toText(clips));
		// One with no sound is left out; a gap over an hour is an hour
		assertEquals(3, read.size());
		assertEquals(clips.get(0), read.get(0));
		assertEquals("steve:站名.ogg", read.get(1).sound);
		assertEquals(36000, read.get(2).gapTenths);
		assertArrayEquals(new long[]{30, 0, 72000}, AnnouncementClips.gapTicks(read));

		assertTrue(AnnouncementClips.parse("not json").isEmpty());
		assertTrue(AnnouncementClips.parse("").isEmpty());
		assertEquals(Collections.emptyList(), AnnouncementClips.single(""));
		assertEquals(1, AnnouncementClips.single("mtr:ticket_barrier").size());
		assertEquals(AnnouncementClips.MAX_CLIPS, AnnouncementClips.parse(AnnouncementClips.toText(Collections.nCopies(20, clips.get(0)))).size());
	}
}
