package mtr.media;

import mtr.audio.AnnouncementSound;
import mtr.audio.AudioDatabase;
import mtr.audio.AudioFiles;
import mtr.image.ImageDatabase;
import mtr.image.ImageFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.JDBC;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A file keeps its id when replaced under another name, and its old names still find it (0.79.0).
 */
class MediaIdsTest {

	@TempDir
	Path folder;

	private static byte[] png(int argb) throws Exception {
		final BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) image.setRGB(x, y, argb);
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ImageIO.write(image, "png", bytes);
		return bytes.toByteArray();
	}

	private static ImageDatabase.Entry only(ImageDatabase database) throws Exception {
		final List<ImageDatabase.Entry> entries = database.entries();
		assertEquals(1, entries.size());
		return entries.get(0);
	}

	@Test
	void aReferenceFindsTheFileWithTheNameNowOrTheOneThatHadItBefore() {
		final List<String[]> entries = Arrays.asList(new String[]{"steve:new.png", "a1"}, new String[]{"alex:map.png", "b2"});
		final Map<String, List<String>> oldNames = new HashMap<>();
		oldNames.put("a1", Arrays.asList("steve:old.png", "steve:Older.png"));
		final Map<String, String> byOldName = MediaIds.byOldName(oldNames);
		assertEquals("a1", MediaIds.resolve("STEVE:NEW.PNG", entries, e -> e[0], e -> e[1], byOldName)[1]);
		assertEquals("a1", MediaIds.resolve("steve:old.png", entries, e -> e[0], e -> e[1], byOldName)[1]);
		assertEquals("a1", MediaIds.resolve("steve:older.png", entries, e -> e[0], e -> e[1], byOldName)[1]);
		assertEquals("b2", MediaIds.resolve("alex:map.png", entries, e -> e[0], e -> e[1], byOldName)[1]);
		assertNull(MediaIds.resolve("alex:old.png", entries, e -> e[0], e -> e[1], byOldName));
		assertNull(MediaIds.resolve("", entries, e -> e[0], e -> e[1], byOldName));
		assertTrue(MediaIds.isId(MediaIds.newId(new HashSet<>())));
	}

	@Test
	void aPictureReplacedUnderAnotherNameKeepsItsIdAndItsOldName() throws Exception {
		final Path path = folder.resolve("pictures.db");
		final byte[] first = png(0xFF112233), second = png(0xFF445566), third = png(0xFF778899);
		final String id;
		try (ImageDatabase database = new ImageDatabase(path)) {
			database.add("steve", "map.png", first, "uuid-steve", "Steve", "maps");
			id = only(database).resourceId;
			assertTrue(MediaIds.isId(id));
			// Uploading under the same name again is an update in place, keeping the id
			database.add("steve", "map.png", second, "uuid-steve", "Steve", "maps");
			assertEquals(id, only(database).resourceId);

			database.replace(id, "plan.png", third);
			final ImageDatabase.Entry replaced = only(database);
			assertEquals(id, replaced.resourceId);
			assertEquals("steve:plan.png", replaced.name);
			assertEquals(ImageFiles.hash(third), replaced.hash);
			assertEquals("maps", replaced.folder);
			assertEquals("uuid-steve", replaced.uploader);
			assertEquals(Collections.singletonList("steve:map.png"), replaced.oldNames);
			// The old content is gone, as nothing names it
			assertNull(database.read(ImageFiles.hash(second)));

			// Its old name is still its own: another upload can't take it
			assertEquals(MediaIds.NAME_USED, assertThrows(IllegalArgumentException.class, () -> database.add("steve", "MAP.png", first, "uuid-steve", "Steve")).getMessage());
			database.add("alex", "other.png", first, "uuid-alex", "Alex");
			final String otherId = database.entries().stream().filter(entry -> entry.name.equals("alex:other.png")).findFirst().get().resourceId;
			assertNotEquals(id, otherId);
			// Nor can another of the player's files be replaced under it, or under another file's name now
			database.add("steve", "two.png", first, "uuid-steve", "Steve");
			final String twoId = database.entries().stream().filter(entry -> entry.name.equals("steve:two.png")).findFirst().get().resourceId;
			assertEquals(MediaIds.NAME_USED, assertThrows(IllegalArgumentException.class, () -> database.replace(twoId, "map.png", first)).getMessage());
			assertEquals(MediaIds.NAME_USED, assertThrows(IllegalArgumentException.class, () -> database.replace(twoId, "plan.png", first)).getMessage());
			database.delete("steve:two.png");
		}
		// After a restart, as it was; going back to its old name swaps the two
		try (ImageDatabase database = new ImageDatabase(path)) {
			final ImageDatabase.Entry entry = database.entries().stream().filter(each -> each.resourceId.equals(id)).findFirst().get();
			assertEquals("steve:plan.png", entry.name);
			assertEquals(Collections.singletonList("steve:map.png"), entry.oldNames);
			database.replace(id, "map.png", second);
			final ImageDatabase.Entry back = database.entries().stream().filter(each -> each.resourceId.equals(id)).findFirst().get();
			assertEquals("steve:map.png", back.name);
			assertEquals(Collections.singletonList("steve:plan.png"), back.oldNames);
			// Deleted, its names are free again
			database.delete("steve:map.png");
			database.add("steve", "plan.png", first, "uuid-steve", "Steve");
			assertTrue(database.entries().stream().anyMatch(each -> each.name.equals("steve:plan.png") && !each.resourceId.equals(id) && each.oldNames.isEmpty()));
		}
	}

	@Test
	void picturesFromBeforeIdsAreGivenOne() throws Exception {
		final Path path = folder.resolve("old.db");
		final byte[] bytes = png(0xFF00FF00);
		// The tables as 0.74 made them, with one picture
		try (Connection connection = new JDBC().connect(JDBC.PREFIX + path, new Properties()); Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE picture_index (name_key TEXT PRIMARY KEY, namespace TEXT NOT NULL, file_name TEXT NOT NULL, hash TEXT NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, size INTEGER NOT NULL, uploader TEXT NOT NULL, uploader_name TEXT NOT NULL, uploaded_at INTEGER NOT NULL)");
			statement.execute("CREATE TABLE picture_data (hash TEXT PRIMARY KEY, data BLOB NOT NULL)");
		}
		try (Connection connection = new JDBC().connect(JDBC.PREFIX + path, new Properties())) {
			try (java.sql.PreparedStatement statement = connection.prepareStatement("INSERT INTO picture_data VALUES (?,?)")) { statement.setString(1, ImageFiles.hash(bytes)); statement.setBytes(2, bytes); statement.executeUpdate(); }
			try (java.sql.PreparedStatement statement = connection.prepareStatement("INSERT INTO picture_index VALUES ('steve:old.png','steve','old.png',?,2,2,?,'u','Steve',1)")) { statement.setString(1, ImageFiles.hash(bytes)); statement.setInt(2, bytes.length); statement.executeUpdate(); }
		}
		final String id;
		try (ImageDatabase database = new ImageDatabase(path)) {
			id = only(database).resourceId;
			assertTrue(MediaIds.isId(id));
		}
		try (ImageDatabase database = new ImageDatabase(path)) {
			assertEquals(id, only(database).resourceId, "the id stays the same once given");
		}
	}

	@Test
	void audioReplacedUnderAnotherNameKeepsItsIdAndItsOldName() throws Exception {
		final Path path = folder.resolve("mtr").resolve(AudioDatabase.FILE_NAME);
		final byte[] first = {1, 2, 3, 4}, second = {5, 6, 7};
		final String key = AnnouncementSound.getNameKey("steve", "next.ogg");
		try (AudioDatabase database = new AudioDatabase(path)) {
			database.write(key, "steve", "next.ogg", AudioFiles.hash(first), first.length, 1000, "u", "Steve", 1, "next", "calls", first);
			final String[] id = new String[1];
			database.readIndex((namespace, fileName, hash, size, durationMillis, uploader, uploaderName, uploadedAt, soundId, fileFolder, resourceId, oldNames) -> id[0] = resourceId);
			assertTrue(MediaIds.isId(id[0]));
			final String newKey = AnnouncementSound.getNameKey("steve", "next_v2.ogg");
			database.replace(newKey, "steve", "next_v2.ogg", AudioFiles.hash(second), second.length, 900, "u", "Steve", 2, "next", "calls", second, AudioFiles.hash(first), id[0], "steve:next.ogg");
			final List<String> rows = new ArrayList<>();
			database.readIndex((namespace, fileName, hash, size, durationMillis, uploader, uploaderName, uploadedAt, soundId, fileFolder, resourceId, oldNames) -> rows.add(fileName + "|" + resourceId.equals(id[0]) + "|" + soundId + "|" + fileFolder + "|" + oldNames));
			assertEquals(Collections.singletonList("next_v2.ogg|true|next|calls|[steve:next.ogg]"), rows);
			assertNull(database.readData(AudioFiles.hash(first)), "the old recording is gone");
			assertEquals(id[0], database.ids.oldNameOwner(key));
			// Deleted, its old names go too
			database.delete(newKey, AudioFiles.hash(second));
			assertNull(database.ids.oldNameOwner(key));
		}
	}
}
