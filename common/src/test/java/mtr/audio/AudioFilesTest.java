package mtr.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AudioFilesTest {

	@TempDir
	Path folder;

	/**
	 * The mod's own sounds are real Ogg Vorbis files from different encoders.
	 */
	private static List<byte[]> sounds() throws Exception {
		final Path directory = Paths.get(AudioFilesTest.class.getResource("/assets/mtr/sounds").toURI());
		final List<byte[]> sounds = new ArrayList<>();
		try (final Stream<Path> files = Files.walk(directory)) {
			for (final Path path : files.filter(path -> path.toString().endsWith(".ogg")).sorted().limit(40).collect(Collectors.toList())) {
				final byte[] bytes = Files.readAllBytes(path);
				if (bytes.length <= AudioFiles.MAX_FILE_BYTES) {
					sounds.add(bytes);
				}
			}
		}
		assertFalse(sounds.isEmpty());
		return sounds;
	}

	@Test
	void acceptsRealFiles() throws Exception {
		for (final byte[] sound : sounds()) {
			final AudioFiles.Info info = AudioFiles.check(sound);
			assertTrue(info.channels == 1 || info.channels == 2);
			assertTrue(info.sampleRate >= 8000);
			assertTrue(info.durationMillis >= 0);
		}
	}

	@Test
	void rejectsDamagedFiles() throws Exception {
		final byte[] sound = sounds().get(0);

		final byte[] changed = sound.clone();
		changed[changed.length / 2] ^= 0x55;
		assertThrows(AudioFiles.InvalidAudioException.class, () -> AudioFiles.check(changed));

		assertThrows(AudioFiles.InvalidAudioException.class, () -> AudioFiles.check(Arrays.copyOf(sound, sound.length - 10)));

		final byte[] extra = Arrays.copyOf(sound, sound.length + 4);
		assertThrows(AudioFiles.InvalidAudioException.class, () -> AudioFiles.check(extra));

		assertThrows(AudioFiles.InvalidAudioException.class, () -> AudioFiles.check(new byte[0]));
		assertThrows(AudioFiles.InvalidAudioException.class, () -> AudioFiles.check("RIFF....WAVEfmt ".getBytes()));
	}

	@Test
	void hashesAndNames() {
		final String hash = AudioFiles.hash(new byte[]{1, 2, 3});
		assertTrue(AudioFiles.isHash(hash));
		assertFalse(AudioFiles.isHash(hash.toUpperCase()));
		assertFalse(AudioFiles.isHash("../" + hash.substring(3)));
		assertEquals("ab", AudioFiles.cleanName(" a\u0000b\n "));
		assertEquals(AudioFiles.MAX_NAME_LENGTH, AudioFiles.cleanName(new String(new char[200]).replace('\0', '站')).length());
		// A file name, never a path, and a long one keeps its extension
		assertEquals(".._a_b.ogg", AudioFiles.cleanName("../a\\b.ogg"));
		final String longName = AudioFiles.cleanName(new String(new char[200]).replace('\0', '站') + ".ogg");
		assertEquals(AudioFiles.MAX_NAME_LENGTH, longName.length());
		assertTrue(longName.endsWith("站.ogg"));
		assertEquals("next.ogg", AudioFiles.withExtension("next"));
		assertEquals("next.ogg", AudioFiles.withExtension("next.ogg"));
		assertEquals("next..ogg", AudioFiles.withExtension("next."));
	}

	@Test
	void databaseKeepsEntriesWithTheirAudio() throws Exception {
		final Path path = folder.resolve("mtr").resolve(AudioDatabase.FILE_NAME);
		final byte[] data = {1, 2, 3, 4};
		final String hash = AudioFiles.hash(data);
		final String steveKey = AnnouncementSound.getNameKey("steve", "下一站.ogg");
		final String alexKey = AnnouncementSound.getNameKey("mtr_player", "next.ogg");
		try (final AudioDatabase database = new AudioDatabase(path)) {
			// Two players add the same recording under their own names
			database.write(steveKey, "steve", "下一站.ogg", hash, data.length, 1000, "uuid1", "Steve", 5, data);
			database.write(alexKey, "mtr_player", "next.ogg", hash, data.length, 1000, "uuid2", "MTR", 6, data);
			// Audio without a name, as a client keeps it, isn't listed
			database.writeData(AudioFiles.hash(new byte[]{9}), new byte[]{9});
		}
		try (final AudioDatabase database = new AudioDatabase(path)) {
			final List<String> names = new ArrayList<>();
			database.readIndex((namespace, fileName, readHash, size, durationMillis, uploader, uploaderName, uploadedAt) -> names.add(AnnouncementSound.getUploadedString(namespace, fileName) + size));
			assertEquals(Arrays.asList("steve:下一站.ogg4", "mtr_player:next.ogg4"), names);
			final List<String> hashes = new ArrayList<>();
			database.readDataHashes(hashes::add);
			assertEquals(2, hashes.size());
			// The audio stays while another name uses it
			database.delete(steveKey, hash);
			assertArrayEquals(data, database.readData(hash));
			database.delete(alexKey, hash);
			assertNull(database.readData(hash));
		}
	}
}
