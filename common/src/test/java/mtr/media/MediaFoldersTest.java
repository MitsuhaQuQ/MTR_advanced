package mtr.media;

import mtr.audio.AnnouncementSound;
import mtr.audio.AudioDatabase;
import mtr.audio.AudioFiles;
import mtr.image.ImageDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.JDBC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Folders of uploaded audio and pictures (0.74.0): the rules for names and paths, and the folder actions on a database.
 */
class MediaFoldersTest {

	@TempDir
	Path folder;

	@Test
	void pathsAreTrimmedAndChecked() {
		assertEquals("", MediaFolders.normalize(null));
		assertEquals("", MediaFolders.normalize(" / "));
		assertEquals("1号线/上行", MediaFolders.normalize(" 1号线 //上行/ "));
		assertEquals("a/b/c/d/e/f/g/h", MediaFolders.normalize("a/b/c/d/e/f/g/h"));
		// Nine levels, a name too long, a backslash, a control character, only dots
		assertThrows(IllegalArgumentException.class, () -> MediaFolders.normalize("a/b/c/d/e/f/g/h/i"));
		assertThrows(IllegalArgumentException.class, () -> MediaFolders.normalize(String.join("", Collections.nCopies(33, "x"))));
		assertThrows(IllegalArgumentException.class, () -> MediaFolders.normalize("a\\b"));
		assertThrows(IllegalArgumentException.class, () -> MediaFolders.normalize("a\nb"));
		assertThrows(IllegalArgumentException.class, () -> MediaFolders.normalize("a/../b"));
		assertThrows(IllegalArgumentException.class, () -> MediaFolders.checkName("a/b"));
		assertThrows(IllegalArgumentException.class, () -> MediaFolders.checkName("  "));
	}

	@Test
	void pathHelpers() {
		assertEquals("a/b", MediaFolders.parent("a/b/c"));
		assertEquals("", MediaFolders.parent("a"));
		assertEquals("c", MediaFolders.name("a/b/c"));
		assertEquals("a/x", MediaFolders.child("a", "x"));
		assertEquals("x", MediaFolders.child("", "x"));
		assertTrue(MediaFolders.within("A/b", "a"));
		assertTrue(MediaFolders.within("a", "a"));
		assertFalse(MediaFolders.within("ab", "a"));
		assertEquals("x/b", MediaFolders.moved("a/b", "a", "x"));
		assertEquals("x", MediaFolders.moved("a", "a", "x"));
		// Deleting a/b: what was in it is now in a
		assertEquals("a", MediaFolders.afterDelete("a/b", "a/b"));
		assertEquals("a/c", MediaFolders.afterDelete("a/b/c", "a/b"));
		assertEquals("", MediaFolders.afterDelete("top", "top"));
		// Folders differing only in case are one, spelled as the first of them in order
		assertEquals(Arrays.asList("A", "a/b", "a/b/c", "x"), MediaFolders.withParents(Arrays.asList("a/b/c", "x", "A")));
	}

	@Test
	void foldersInTheAudioDatabase() throws Exception {
		final Path path = folder.resolve("mtr").resolve(AudioDatabase.FILE_NAME);
		final byte[] one = {1, 2};
		final byte[] two = {3, 4};
		final String keyOne = AnnouncementSound.getNameKey("steve", "one.ogg");
		final String keyTwo = AnnouncementSound.getNameKey("steve", "two.ogg");
		final String keyAlex = AnnouncementSound.getNameKey("alex", "three.ogg");
		try (final AudioDatabase database = new AudioDatabase(path)) {
			database.write(keyOne, "steve", "one.ogg", AudioFiles.hash(one), 2, 100, "u1", "Steve", 1, "one", "1号线/上行", one);
			database.write(keyTwo, "steve", "two.ogg", AudioFiles.hash(two), 2, 100, "u1", "Steve", 2, "two", "", two);
			database.write(keyAlex, "alex", "three.ogg", AudioFiles.hash(two), 2, 100, "u2", "Alex", 3, "three", "", two);
			// The folder a file is written into is kept, with its parents; an empty one can be made
			assertEquals(Arrays.asList("1号线", "1号线/上行"), paths(database.folders, "u1"));
			database.folders.create("u1", "Steve", "1号线/下行");
			assertEquals(Arrays.asList("1号线", "1号线/上行", "1号线/下行"), paths(database.folders, "u1"));

			// Moving files: only the owner's
			database.folders.move("u1", "Steve", Arrays.asList(keyTwo, keyAlex), "1号线/下行");
			assertEquals("1号线/下行", database.folders.fileFolders().get(keyTwo));
			assertEquals("", database.folders.fileFolders().get(keyAlex));

			// Renaming takes everything inside along
			database.folders.rename("u1", "Steve", "1号线", "Line 1", database.folders.folders().stream().filter(f -> f.owner.equals("u1")).map(f -> f.path).collect(Collectors.toList()));
			assertEquals(Arrays.asList("Line 1", "Line 1/上行", "Line 1/下行"), paths(database.folders, "u1"));
			assertEquals("Line 1/上行", database.folders.fileFolders().get(keyOne));

			// Deleting moves what was inside up a level; no file is deleted
			database.folders.delete("u1", "Steve", "Line 1/上行");
			assertEquals("Line 1", database.folders.fileFolders().get(keyOne));
			database.folders.delete("u1", "Steve", "Line 1");
			assertEquals(Collections.singletonList("下行"), paths(database.folders, "u1"));
			assertEquals("", database.folders.fileFolders().get(keyOne));
			assertEquals("下行", database.folders.fileFolders().get(keyTwo));
		}
		// The folders are there after opening again, and a replaced file keeps the folder it is given
		try (final AudioDatabase database = new AudioDatabase(path)) {
			final Map<String, String> folders = new HashMap<>();
			database.readIndex((namespace, fileName, hash, size, durationMillis, uploader, uploaderName, uploadedAt, soundId, fileFolder, resourceId, oldNames) -> folders.put(fileName, fileFolder));
			assertEquals("下行", folders.get("two.ogg"));
			database.replace(keyTwo, "steve", "two.ogg", AudioFiles.hash(one), 2, 100, "u1", "Steve", 4, "two", "下行", one, AudioFiles.hash(two));
			assertEquals("下行", database.folders.fileFolders().get(keyTwo));
		}
	}

	@Test
	void folderActionsCheckWhatTheyAreGiven() throws Exception {
		final Path path = folder.resolve("actions").resolve(AudioDatabase.FILE_NAME);
		final byte[] data = {5, 6};
		final String key = AnnouncementSound.getNameKey("alex", "a.ogg");
		try (final AudioDatabase database = new AudioDatabase(path)) {
			database.write(key, "alex", "a.ogg", AudioFiles.hash(data), 2, 100, "u2", "Alex", 1, "a", "", data);
			final Map<String, String> owners = new HashMap<>();
			owners.put(key, "u2");
			assertEquals(MediaFolders.DONE, MediaFolders.apply(database.folders, MediaFolders.CREATE, "u1", "Steve", "", "上行", Collections.emptyList(), owners::get, Collections.emptyList()));
			// The same name again, a bad name, a file of someone else's
			assertEquals(MediaFolders.INVALID, MediaFolders.apply(database.folders, MediaFolders.CREATE, "u1", "Steve", "", "上行", Collections.emptyList(), owners::get, Collections.singletonList("上行")));
			assertEquals(MediaFolders.INVALID, MediaFolders.apply(database.folders, MediaFolders.CREATE, "u1", "Steve", "", "a/b", Collections.emptyList(), owners::get, Collections.emptyList()));
			assertEquals(MediaFolders.NO_PERMISSION, MediaFolders.apply(database.folders, MediaFolders.MOVE, "u1", "Steve", "", "上行", Collections.singletonList(key), owners::get, Collections.emptyList()));
			// A folder can't be renamed onto another one
			MediaFolders.apply(database.folders, MediaFolders.CREATE, "u1", "Steve", "", "下行", Collections.emptyList(), owners::get, Collections.emptyList());
			assertEquals(MediaFolders.INVALID, MediaFolders.apply(database.folders, MediaFolders.RENAME, "u1", "Steve", "上行", "下行", Collections.emptyList(), owners::get, Arrays.asList("上行", "下行")));
			assertEquals(MediaFolders.DONE, MediaFolders.apply(database.folders, MediaFolders.MOVE, "u2", "Alex", "", "Alex 的", Collections.singletonList(key), owners::get, Collections.emptyList()));
			assertEquals("Alex 的", database.folders.fileFolders().get(key));
		}
	}

	@Test
	void olderDatabasesGetTheFolderColumn() throws Exception {
		final Path path = folder.resolve("old").resolve(AudioDatabase.FILE_NAME);
		Files.createDirectories(path.getParent());
		try (final Connection connection = new JDBC().connect(JDBC.PREFIX + path, new Properties()); final Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE audio_index (name_key TEXT PRIMARY KEY, namespace TEXT NOT NULL, file_name TEXT NOT NULL, hash TEXT NOT NULL, size INTEGER NOT NULL, duration_millis INTEGER NOT NULL, uploader TEXT NOT NULL, uploader_name TEXT NOT NULL, uploaded_at INTEGER NOT NULL) WITHOUT ROWID");
			statement.execute("CREATE TABLE audio_data (hash TEXT PRIMARY KEY, data BLOB NOT NULL)");
			statement.execute("INSERT INTO audio_data VALUES ('" + AudioFiles.hash(new byte[]{7}) + "', x'07')");
			statement.execute("INSERT INTO audio_index VALUES ('steve:old.ogg', 'steve', 'old.ogg', '" + AudioFiles.hash(new byte[]{7}) + "', 1, 100, 'u1', 'Steve', 1)");
		}
		try (final AudioDatabase database = new AudioDatabase(path)) {
			final Map<String, String> folders = new HashMap<>();
			database.readIndex((namespace, fileName, hash, size, durationMillis, uploader, uploaderName, uploadedAt, soundId, fileFolder, resourceId, oldNames) -> folders.put(fileName, fileFolder));
			assertEquals("", folders.get("old.ogg"));
			assertTrue(database.folders.folders().isEmpty());
		}
	}

	@Test
	void foldersInTheImageDatabase() throws Exception {
		final byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
		try (final ImageDatabase database = new ImageDatabase(folder.resolve("pictures.db"))) {
			database.add("steve", "map.png", png, "u1", "Steve", "站名/甲站");
			database.add("steve", "other.png", png, "u1", "Steve");
			final List<ImageDatabase.Entry> entries = database.entries();
			assertEquals("站名/甲站", entries.stream().filter(entry -> entry.fileName.equals("map.png")).findFirst().orElseThrow(AssertionError::new).folder);
			assertEquals("", entries.stream().filter(entry -> entry.fileName.equals("other.png")).findFirst().orElseThrow(AssertionError::new).folder);
			assertEquals(Arrays.asList("站名", "站名/甲站"), paths(database.folders, "u1"));
			database.folders.move("u1", "Steve", Collections.singletonList("steve:other.png"), "站名");
			assertEquals("站名", database.entries().stream().filter(entry -> entry.fileName.equals("other.png")).findFirst().orElseThrow(AssertionError::new).folder);
		}
	}

	private static List<String> paths(MediaFolders.Store store, String owner) throws Exception {
		return store.folders().stream().filter(folder -> folder.owner.equals(owner)).map(folder -> folder.path).sorted().collect(Collectors.toList());
	}
}
