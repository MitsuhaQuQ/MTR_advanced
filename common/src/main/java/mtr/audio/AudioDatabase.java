package mtr.audio;

import org.sqlite.JDBC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * The announcement audio of a save, in one file next to the rail data ({@code mtr/announce.db}) rather than a file for
 * each recording. The index has a row for each name players use ({@link AnnouncementSound}): its namespace as it was made,
 * its file name, who added it and the hash of its audio. It is read at start. The audio is kept once for each hash, however many names point to it,
 * and is only read when sent. Names only have to differ between the files of one player; inside, files go by hash.
 * A client keeps its copies the same way, with the audio only.
 */
public final class AudioDatabase implements AutoCloseable {

	public static final String FILE_NAME = "announce.db";

	private final Connection connection;
	private final PreparedStatement writeIndexStatement;
	private final PreparedStatement writeDataStatement;
	private final PreparedStatement readDataStatement;
	private final PreparedStatement deleteIndexStatement;
	private final PreparedStatement deleteUnusedDataStatement;

	public AudioDatabase(Path path) throws Exception {
		Files.createDirectories(path.getParent());
		connection = new JDBC().connect(JDBC.PREFIX + path, new Properties());
		try (final Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA journal_mode=WAL");
			statement.execute("PRAGMA synchronous=NORMAL");
			// The key is the name in lowercase, as names are matched without case
			statement.execute("CREATE TABLE IF NOT EXISTS audio_index (name_key TEXT PRIMARY KEY, namespace TEXT NOT NULL, file_name TEXT NOT NULL, hash TEXT NOT NULL, size INTEGER NOT NULL, duration_millis INTEGER NOT NULL, uploader TEXT NOT NULL, uploader_name TEXT NOT NULL, uploaded_at INTEGER NOT NULL) WITHOUT ROWID");
			statement.execute("CREATE INDEX IF NOT EXISTS audio_index_hash ON audio_index (hash)");
			statement.execute("CREATE TABLE IF NOT EXISTS audio_data (hash TEXT PRIMARY KEY, data BLOB NOT NULL)");
		} catch (SQLException e) {
			connection.close();
			throw e;
		}
		writeIndexStatement = connection.prepareStatement("INSERT OR REPLACE INTO audio_index (name_key, namespace, file_name, hash, size, duration_millis, uploader, uploader_name, uploaded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
		writeDataStatement = connection.prepareStatement("INSERT OR IGNORE INTO audio_data (hash, data) VALUES (?, ?)");
		readDataStatement = connection.prepareStatement("SELECT data FROM audio_data WHERE hash = ?");
		deleteIndexStatement = connection.prepareStatement("DELETE FROM audio_index WHERE name_key = ?");
		deleteUnusedDataStatement = connection.prepareStatement("DELETE FROM audio_data WHERE hash = ? AND NOT EXISTS (SELECT 1 FROM audio_index WHERE hash = ?)");
	}

	/**
	 * Names whose audio is missing or of a different size are left out, as clients check what they get anyway.
	 */
	public void readIndex(IndexConsumer consumer) throws SQLException {
		try (final Statement statement = connection.createStatement(); final ResultSet resultSet = statement.executeQuery("SELECT i.namespace, i.file_name, i.hash, i.size, i.duration_millis, i.uploader, i.uploader_name, i.uploaded_at FROM audio_index i JOIN audio_data d ON d.hash = i.hash WHERE length(d.data) = i.size ORDER BY i.uploaded_at")) {
			while (resultSet.next()) {
				consumer.accept(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3), resultSet.getInt(4), resultSet.getLong(5), resultSet.getString(6), resultSet.getString(7), resultSet.getLong(8));
			}
		}
	}

	/**
	 * The audio held without a name, which is how a client keeps the files of every server it has been on.
	 */
	public void readDataHashes(Consumer<String> consumer) throws SQLException {
		try (final Statement statement = connection.createStatement(); final ResultSet resultSet = statement.executeQuery("SELECT hash FROM audio_data")) {
			while (resultSet.next()) {
				consumer.accept(resultSet.getString(1));
			}
		}
	}

	public void writeData(String hash, byte[] data) throws SQLException {
		writeDataStatement.setString(1, hash);
		writeDataStatement.setBytes(2, data);
		writeDataStatement.executeUpdate();
	}

	public byte[] readData(String hash) throws SQLException {
		readDataStatement.setString(1, hash);
		try (final ResultSet resultSet = readDataStatement.executeQuery()) {
			return resultSet.next() ? resultSet.getBytes(1) : null;
		}
	}

	/**
	 * Writes a name and its audio together, so neither is ever there without the other. Audio already kept for another
	 * name is left as it is.
	 */
	public void write(String nameKey, String namespace, String fileName, String hash, int size, long durationMillis, String uploader, String uploaderName, long uploadedAt, byte[] data) throws SQLException {
		connection.setAutoCommit(false);
		try {
			writeData(hash, data);
			writeIndexStatement.setString(1, nameKey);
			writeIndexStatement.setString(2, namespace);
			writeIndexStatement.setString(3, fileName);
			writeIndexStatement.setString(4, hash);
			writeIndexStatement.setInt(5, size);
			writeIndexStatement.setLong(6, durationMillis);
			writeIndexStatement.setString(7, uploader);
			writeIndexStatement.setString(8, uploaderName);
			writeIndexStatement.setLong(9, uploadedAt);
			writeIndexStatement.executeUpdate();
			connection.commit();
		} catch (SQLException e) {
			connection.rollback();
			throw e;
		} finally {
			connection.setAutoCommit(true);
		}
	}

	/**
	 * Deletes a name, and its audio once no other name uses it.
	 */
	public void delete(String nameKey, String hash) throws SQLException {
		connection.setAutoCommit(false);
		try {
			deleteIndexStatement.setString(1, nameKey);
			deleteIndexStatement.executeUpdate();
			deleteUnusedDataStatement.setString(1, hash);
			deleteUnusedDataStatement.setString(2, hash);
			deleteUnusedDataStatement.executeUpdate();
			connection.commit();
		} catch (SQLException e) {
			connection.rollback();
			throw e;
		} finally {
			connection.setAutoCommit(true);
		}
	}

	@Override
	public void close() throws SQLException {
		connection.close();
	}

	@FunctionalInterface
	public interface IndexConsumer {
		void accept(String namespace, String fileName, String hash, int size, long durationMillis, String uploader, String uploaderName, long uploadedAt);
	}
}
