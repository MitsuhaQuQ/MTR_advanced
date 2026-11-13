package mtr.image;

import org.sqlite.JDBC;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** As for audio: names have owners, while image bytes are stored once by SHA-256. */
public final class ImageDatabase implements AutoCloseable {
	private final Connection connection;
	/** Folders of the uploaded pictures (0.74.0) */
	public final mtr.media.MediaFolders.Store folders;
	/** Each picture's id and the names it had before (0.79.0) */
	public final mtr.media.MediaIds.Store ids;
	public ImageDatabase(Path path) throws Exception {
		Files.createDirectories(path.getParent());
		connection = new JDBC().connect(JDBC.PREFIX + path, new Properties());
		try (Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA journal_mode=WAL");
			statement.execute("CREATE TABLE IF NOT EXISTS picture_index (name_key TEXT PRIMARY KEY, namespace TEXT NOT NULL, file_name TEXT NOT NULL, hash TEXT NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, size INTEGER NOT NULL, uploader TEXT NOT NULL, uploader_name TEXT NOT NULL, uploaded_at INTEGER NOT NULL)");
			statement.execute("CREATE INDEX IF NOT EXISTS picture_index_hash ON picture_index(hash)");
			statement.execute("CREATE TABLE IF NOT EXISTS picture_data (hash TEXT PRIMARY KEY, data BLOB NOT NULL)");
		}
		folders = new mtr.media.MediaFolders.Store(connection, "picture_folders", "picture_index");
		ids = new mtr.media.MediaIds.Store(connection, "picture_index", "picture_old_names");
	}
	public List<Entry> entries() throws SQLException {
		final List<Entry> entries = new ArrayList<>();
		final Map<String, List<String>> oldNames = ids.oldNames();
		try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT i.namespace,i.file_name,i.hash,i.width,i.height,i.size,i.uploader,i.uploader_name,i.uploaded_at,i.folder,i.resource_id FROM picture_index i JOIN picture_data d ON d.hash=i.hash WHERE length(d.data)=i.size ORDER BY i.uploaded_at DESC,i.name_key")) {
			while (rows.next()) entries.add(new Entry(rows.getString(1), rows.getString(2), rows.getString(3), rows.getInt(4), rows.getInt(5), rows.getInt(6), rows.getString(7), rows.getString(8), rows.getLong(9), rows.getString(10), rows.getString(11), oldNames.getOrDefault(rows.getString(11), Collections.emptyList())));
		}
		return entries;
	}
	/** At the uploader's top */
	public void add(String namespace, String fileName, byte[] bytes, String uploader, String uploaderName) throws Exception {
		add(namespace, fileName, bytes, uploader, uploaderName, "");
	}
	/**
	 * @param folder the folder it goes in ({@link mtr.media.MediaFolders}), kept along with its parents
	 */
	public void add(String namespace, String fileName, byte[] bytes, String uploader, String uploaderName, String folder) throws Exception {
		final String reference = namespace + ":" + fileName;
		if (!ImageFiles.isReference(reference) || !fileName.endsWith(ImageFiles.extensionOf(bytes))) {
			throw new IllegalArgumentException("gui.mtr.image_invalid");
		}
		final int[] size = ImageFiles.check(bytes);
		final String nameKey = reference.toLowerCase(Locale.ROOT);
		// The name another picture had before is still that one's (0.79.0)
		final String id = ids.idFor(nameKey);
		final String oldOwner = ids.oldNameOwner(nameKey);
		if (oldOwner != null && !oldOwner.equals(id)) throw new IllegalArgumentException(mtr.media.MediaIds.NAME_USED);
		connection.setAutoCommit(false);
		try {
			try (PreparedStatement statement = connection.prepareStatement("INSERT OR IGNORE INTO picture_data VALUES (?,?)")) {
				statement.setString(1, ImageFiles.hash(bytes)); statement.setBytes(2, bytes); statement.executeUpdate();
			}
			try (PreparedStatement statement = connection.prepareStatement("INSERT OR REPLACE INTO picture_index (name_key,namespace,file_name,hash,width,height,size,uploader,uploader_name,uploaded_at,folder,resource_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
				statement.setString(1, nameKey); statement.setString(2, namespace); statement.setString(3, fileName); statement.setString(4, ImageFiles.hash(bytes)); statement.setInt(5, size[0]); statement.setInt(6, size[1]); statement.setInt(7, bytes.length); statement.setString(8, uploader); statement.setString(9, uploaderName); statement.setLong(10, System.currentTimeMillis()); statement.setString(11, folder); statement.setString(12, id); statement.executeUpdate();
			}
			folders.insertWithParents(uploader, uploaderName, folder);
			deleteUnused(); connection.commit();
		} catch (Exception e) { connection.rollback(); throw e; }
		finally { connection.setAutoCommit(true); }
	}
	/**
	 * Puts other content in place of a picture's, under a new file name if it is given one (0.79.0): its id, uploader and
	 * folder stay, and its old name stays its own, so what refers to it by that name shows the new picture.
	 *
	 * @throws IllegalArgumentException if the new name is another picture's, now or before
	 */
	public void replace(String id, String fileName, byte[] bytes) throws Exception {
		String namespace = null, oldFileName = null;
		try (PreparedStatement statement = connection.prepareStatement("SELECT namespace,file_name FROM picture_index WHERE resource_id=?")) {
			statement.setString(1, id);
			try (ResultSet row = statement.executeQuery()) { if (row.next()) { namespace = row.getString(1); oldFileName = row.getString(2); } }
		}
		if (namespace == null) throw new IllegalArgumentException("gui.mtr.image_no_permission");
		final String reference = namespace + ":" + fileName, oldReference = namespace + ":" + oldFileName;
		if (!ImageFiles.isReference(reference) || !fileName.endsWith(ImageFiles.extensionOf(bytes))) throw new IllegalArgumentException("gui.mtr.image_invalid");
		final String nameKey = reference.toLowerCase(Locale.ROOT);
		if (!nameKey.equals(oldReference.toLowerCase(Locale.ROOT))) {
			try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM picture_index WHERE name_key=?")) {
				statement.setString(1, nameKey);
				try (ResultSet row = statement.executeQuery()) { if (row.next()) throw new IllegalArgumentException(mtr.media.MediaIds.NAME_USED); }
			}
		}
		final String oldOwner = ids.oldNameOwner(nameKey);
		if (oldOwner != null && !oldOwner.equals(id)) throw new IllegalArgumentException(mtr.media.MediaIds.NAME_USED);
		final int[] size = ImageFiles.check(bytes);
		connection.setAutoCommit(false);
		try {
			try (PreparedStatement statement = connection.prepareStatement("INSERT OR IGNORE INTO picture_data VALUES (?,?)")) {
				statement.setString(1, ImageFiles.hash(bytes)); statement.setBytes(2, bytes); statement.executeUpdate();
			}
			try (PreparedStatement statement = connection.prepareStatement("UPDATE picture_index SET name_key=?,file_name=?,hash=?,width=?,height=?,size=?,uploaded_at=? WHERE resource_id=?")) {
				statement.setString(1, nameKey); statement.setString(2, fileName); statement.setString(3, ImageFiles.hash(bytes)); statement.setInt(4, size[0]); statement.setInt(5, size[1]); statement.setInt(6, bytes.length); statement.setLong(7, System.currentTimeMillis()); statement.setString(8, id); statement.executeUpdate();
			}
			ids.rename(id, oldReference, reference);
			deleteUnused(); connection.commit();
		} catch (Exception e) { connection.rollback(); throw e; }
		finally { connection.setAutoCommit(true); }
	}
	public byte[] read(String hash) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT data FROM picture_data WHERE hash=?")) {
			statement.setString(1, hash);
			try (ResultSet row = statement.executeQuery()) { return row.next() ? row.getBytes(1) : null; }
		}
	}
	/** The client cache stores only content, shared by all worlds and servers. */
	public void writeData(String hash, byte[] bytes) throws SQLException {
		if (bytes.length > ImageFiles.MAX_BYTES || !ImageFiles.hash(bytes).equals(hash)) throw new IllegalArgumentException("Invalid cached image");
		try (PreparedStatement statement = connection.prepareStatement("INSERT OR REPLACE INTO picture_data VALUES (?,?)")) { statement.setString(1, hash); statement.setBytes(2, bytes); statement.executeUpdate(); }
	}
	public void deleteData(String hash) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("DELETE FROM picture_data WHERE hash=?")) { statement.setString(1, hash); statement.executeUpdate(); }
	}
	public void delete(String reference) throws SQLException {
		connection.setAutoCommit(false);
		try {
			// Its old names are free again (0.79.0)
			try (PreparedStatement statement = connection.prepareStatement("DELETE FROM picture_old_names WHERE resource_id IN (SELECT resource_id FROM picture_index WHERE name_key=?)")) { statement.setString(1, reference.toLowerCase(Locale.ROOT)); statement.executeUpdate(); }
			try (PreparedStatement statement = connection.prepareStatement("DELETE FROM picture_index WHERE name_key=?")) { statement.setString(1, reference.toLowerCase(Locale.ROOT)); statement.executeUpdate(); }
			deleteUnused(); connection.commit();
		} catch (SQLException e) { connection.rollback(); throw e; }
		finally { connection.setAutoCommit(true); }
	}
	private void deleteUnused() throws SQLException {
		try (Statement statement = connection.createStatement()) { statement.executeUpdate("DELETE FROM picture_data WHERE NOT EXISTS (SELECT 1 FROM picture_index WHERE picture_index.hash=picture_data.hash)"); }
	}
	@Override public void close() throws SQLException { connection.close(); }
	public static final class Entry {
		public final String namespace, fileName, hash, name, uploader, uploaderName;
		public final int width, height, size;
		public final long uploadedAt;
		/** The folder it is in under its uploader ({@link mtr.media.MediaFolders}), empty at the top */
		public final String folder;
		/** Its id, which stays when it is replaced, and the names it had before, which still find it (0.79.0) */
		public final String resourceId;
		public final List<String> oldNames;
		public Entry(String namespace, String fileName, String hash, int width, int height, int size, String uploader, String uploaderName, long uploadedAt) {
			this(namespace, fileName, hash, width, height, size, uploader, uploaderName, uploadedAt, "");
		}
		public Entry(String namespace, String fileName, String hash, int width, int height, int size, String uploader, String uploaderName, long uploadedAt, String folder) {
			this(namespace, fileName, hash, width, height, size, uploader, uploaderName, uploadedAt, folder, "", Collections.emptyList());
		}
		public Entry(String namespace, String fileName, String hash, int width, int height, int size, String uploader, String uploaderName, long uploadedAt, String folder, String resourceId, List<String> oldNames) {
			this.namespace = namespace; this.fileName = fileName; this.hash = hash; this.name = namespace + ":" + fileName; this.width = width; this.height = height; this.size = size; this.uploader = uploader; this.uploaderName = uploaderName; this.uploadedAt = uploadedAt; this.folder = folder == null ? "" : folder;
			this.resourceId = resourceId == null ? "" : resourceId; this.oldNames = oldNames == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(oldNames));
		}
	}
}
