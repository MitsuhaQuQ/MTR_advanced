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
			database.write(steveKey, "steve", "下一站.ogg", hash, data.length, 1000, "uuid1", "Steve", 5, "next_station", data);
			database.write(alexKey, "mtr_player", "next.ogg", hash, data.length, 1000, "uuid2", "MTR", 6, "next", data);
			// Audio without a name, as a client keeps it, isn't listed
			database.writeData(AudioFiles.hash(new byte[]{9}), new byte[]{9});
		}
		try (final AudioDatabase database = new AudioDatabase(path)) {
			final List<String> names = new ArrayList<>();
			database.readIndex((namespace, fileName, readHash, size, durationMillis, uploader, uploaderName, uploadedAt, soundId) -> names.add(AnnouncementSound.getUploadedString(namespace, fileName) + size + soundId));
			assertEquals(Arrays.asList("steve:下一站.ogg4next_station", "mtr_player:next.ogg4next"), names);
			database.setSoundId(steveKey, "station/next");
			final List<String> soundIds = new ArrayList<>();
			database.readIndex((namespace, fileName, readHash, size, durationMillis, uploader, uploaderName, uploadedAt, soundId) -> soundIds.add(soundId));
			assertEquals(Arrays.asList("station/next", "next"), soundIds);
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

	@Test
	void anIndexFromBeforeSoundIdsGetsTheColumn() throws Exception {
		final Path path = folder.resolve("old").resolve(AudioDatabase.FILE_NAME);
		java.nio.file.Files.createDirectories(path.getParent());
		final byte[] data = {5, 6};
		final String hash = AudioFiles.hash(data);
		// The tables as they were made before sound IDs
		try (final java.sql.Connection connection = new org.sqlite.JDBC().connect(org.sqlite.JDBC.PREFIX + path, new java.util.Properties()); final java.sql.Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE audio_index (name_key TEXT PRIMARY KEY, namespace TEXT NOT NULL, file_name TEXT NOT NULL, hash TEXT NOT NULL, size INTEGER NOT NULL, duration_millis INTEGER NOT NULL, uploader TEXT NOT NULL, uploader_name TEXT NOT NULL, uploaded_at INTEGER NOT NULL) WITHOUT ROWID");
			statement.execute("CREATE TABLE audio_data (hash TEXT PRIMARY KEY, data BLOB NOT NULL)");
			statement.execute("INSERT INTO audio_index VALUES ('steve:next.ogg', 'steve', 'next.ogg', '" + hash + "', 2, 500, 'uuid1', 'Steve', 7)");
			try (final java.sql.PreparedStatement insert = connection.prepareStatement("INSERT INTO audio_data VALUES (?, ?)")) {
				insert.setString(1, hash);
				insert.setBytes(2, data);
				insert.executeUpdate();
			}
		}
		try (final AudioDatabase database = new AudioDatabase(path)) {
			final List<String> rows = new ArrayList<>();
			database.readIndex((namespace, fileName, readHash, size, durationMillis, uploader, uploaderName, uploadedAt, soundId) -> rows.add(fileName + "|" + soundId + "|" + uploadedAt));
			assertEquals(Arrays.asList("next.ogg||7"), rows);
		}
	}
}
