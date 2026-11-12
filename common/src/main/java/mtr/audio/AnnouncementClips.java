package mtr.audio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An announcer's sounds played one after another (0.72.0), each with the silence after it before the next, as the audio
 * speaker's clips ({@link SpeakerSettings.Clip}) but of any sound an announcer takes ({@link AnnouncementSound}: a sound of
 * the game, a file added to the server or a web address). Kept and sent as text, {@code [["sound",gapTenths],…]}, so the
 * announcer's save and its settings packet carry it as one more string.
 */
public final class AnnouncementClips {

	public static final int MAX_CLIPS = 16;

	private AnnouncementClips() {
	}

	public static final class Clip {

		public final String sound;
		/** The silence after it, in tenths of a second, 0 to {@link SpeakerSettings#MAX_TENTHS} */
		public final int gapTenths;

		public Clip(String sound, int gapTenths) {
			this.sound = AnnouncementSound.clean(sound);
			this.gapTenths = Math.max(0, Math.min(SpeakerSettings.MAX_TENTHS, gapTenths));
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Clip && ((Clip) other).sound.equals(sound) && ((Clip) other).gapTenths == gapTenths;
		}

		@Override
		public int hashCode() {
			return Objects.hash(sound, gapTenths);
		}
	}

	/**
	 * @return one clip of the sound, or none for no sound: an announcer from before 0.72.0
	 */
	public static List<Clip> single(String sound) {
		final Clip clip = new Clip(sound, 0);
		return clip.sound.isEmpty() ? Collections.emptyList() : Collections.singletonList(clip);
	}

	/**
	 * @return the clips written by {@link #toText}, those with no sound left out and at most {@link #MAX_CLIPS}; none for
	 * text that doesn't read
	 */
	public static List<Clip> parse(String text) {
		final List<Clip> clips = new ArrayList<>();
		if (text == null || text.isEmpty()) {
			return clips;
		}
		try {
			for (final JsonElement element : JsonParser.parseString(text).getAsJsonArray()) {
				final JsonArray pair = element.getAsJsonArray();
				final Clip clip = new Clip(pair.get(0).getAsString(), pair.size() > 1 ? pair.get(1).getAsInt() : 0);
				if (!clip.sound.isEmpty() && clips.size() < MAX_CLIPS) {
					clips.add(clip);
				}
			}
		} catch (RuntimeException e) {
			clips.clear();
		}
		return clips;
	}

	public static String toText(List<Clip> clips) {
		final JsonArray array = new JsonArray();
		for (final Clip clip : clips) {
			if (clip.sound.isEmpty() || array.size() >= MAX_CLIPS) {
				continue;
			}
			final JsonArray pair = new JsonArray();
			pair.add(clip.sound);
			pair.add(clip.gapTenths);
			array.add(pair);
		}
		return array.toString();
	}

	/** The gap after each clip in ticks (the last clip's is not used) */
	public static long[] gapTicks(List<Clip> clips) {
		final long[] gaps = new long[clips.size()];
		for (int i = 0; i < gaps.length; i++) {
			gaps[i] = SpeakerSettings.tenthsToTicks(clips.get(i).gapTenths);
		}
		return gaps;
	}
}
