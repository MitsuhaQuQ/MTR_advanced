package mtr.client;

import org.sqlite.JDBC;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Map;
import java.util.Properties;

/**
 * The file behind the dashboard map, one for each world and dimension. Everything in it can be sent or scanned again, so
 * {@link MapDataCache} replaces a file it can't read with an empty one.
 */
public final class MapCacheDatabase implements AutoCloseable {

	private final Connection connection;
	private final PreparedStatement writeEntryStatement;
	private final PreparedStatement deleteEntryStatement;
	private final PreparedStatement writeTileStatement;

	/**
	 * The side of a terrain tile, which is one chunk.
	 */
	public static final int TILE_SIZE = 16;
	public static final int TILE_COLORS = TILE_SIZE * TILE_SIZE;
	private static final int BYTES_PER_COLOR = 3;

	public MapCacheDatabase(Path path) throws SQLException, IOException {
		Files.createDirectories(path.getParent());
		connection = connect(path);
		writeEntryStatement = connection.prepareStatement("INSERT OR REPLACE INTO entries (kind, key, hash, payload) VALUES (?, ?, ?, ?)");
		deleteEntryStatement = connection.prepareStatement("DELETE FROM entries WHERE kind = ? AND key = ?");
		writeTileStatement = connection.prepareStatement("INSERT OR REPLACE INTO tiles (key, colors) VALUES (?, ?)");
	}

	public void readEntries(EntryConsumer consumer) throws SQLException {
		try (final Statement statement = connection.createStatement(); final ResultSet resultSet = statement.executeQuery("SELECT kind, key, hash, payload FROM entries")) {
			while (resultSet.next()) {
				consumer.accept(resultSet.getInt(1), resultSet.getLong(2), resultSet.getLong(3), toLongs(resultSet.getBytes(4)));
			}
		}
	}

	public void readTiles(TileConsumer consumer) throws SQLException {
		try (final Statement statement = connection.createStatement(); final ResultSet resultSet = statement.executeQuery("SELECT key, colors FROM tiles")) {
			while (resultSet.next()) {
				final int[] colors = toColors(resultSet.getBytes(2));
				if (colors != null) {
					consumer.accept(resultSet.getLong(1), colors);
				}
			}
		}
	}

	public void writeEntry(int kind, long key, long hash, long[] payload) throws SQLException {
		writeEntryStatement.setInt(1, kind);
		writeEntryStatement.setLong(2, key);
		writeEntryStatement.setLong(3, hash);
		writeEntryStatement.setBytes(4, toBytes(payload));
		writeEntryStatement.executeUpdate();
	}

	public void deleteEntries(int kind, Collection<Long> keys) throws SQLException {
		for (final long key : keys) {
			deleteEntryStatement.setInt(1, kind);
			deleteEntryStatement.setLong(2, key);
			deleteEntryStatement.executeUpdate();
		}
	}

	public void writeTiles(Map<Long, int[]> tiles) throws SQLException {
		for (final Map.Entry<Long, int[]> tile : tiles.entrySet()) {
			writeTileStatement.setLong(1, tile.getKey());
			writeTileStatement.setBytes(2, toBytes(tile.getValue()));
			writeTileStatement.executeUpdate();
		}
	}

	public void commit() throws SQLException {
		connection.commit();
	}

	@Override
	public void close() throws SQLException {
		connection.commit();
		connection.close();
	}

	/**
	 * @return a name that works as a file name, which stays different from other names that end up with the same characters
	 */
	public static String getSafeName(String name) {
		final String safeName = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("[. ]+$", "_");
		return safeName.equals(name) ? name : safeName + "-" + Integer.toHexString(name.hashCode());
	}

	public static long getTileKey(int chunkX, int chunkZ) {
		return (long) chunkX << Integer.SIZE | chunkZ & 0xFFFFFFFFL;
	}

	public static int getTileX(long key) {
		return (int) (key >> Integer.SIZE);
	}

	public static int getTileZ(long key) {
		return (int) key;
	}

	private static Connection connect(Path path) throws SQLException {
		final Connection connection = new JDBC().connect(JDBC.PREFIX + path, new Properties());
		try (final Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA journal_mode=WAL");
			statement.execute("PRAGMA synchronous=NORMAL");
			statement.execute("CREATE TABLE IF NOT EXISTS entries (kind INTEGER NOT NULL, key INTEGER NOT NULL, hash INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY (kind, key)) WITHOUT ROWID");
			statement.execute("CREATE TABLE IF NOT EXISTS tiles (key INTEGER PRIMARY KEY, colors BLOB NOT NULL)");
			connection.setAutoCommit(false);
			return connection;
		} catch (SQLException e) {
			connection.close();
			throw e;
		}
	}

	private static byte[] toBytes(long[] values) {
		final ByteBuffer buffer = ByteBuffer.allocate(values.length * Long.BYTES);
		buffer.asLongBuffer().put(values);
		return buffer.array();
	}

	private static long[] toLongs(byte[] bytes) {
		final long[] values = new long[bytes.length / Long.BYTES];
		ByteBuffer.wrap(bytes).asLongBuffer().get(values);
		return values;
	}

	private static byte[] toBytes(int[] colors) {
		final byte[] bytes = new byte[colors.length * BYTES_PER_COLOR];
		for (int i = 0; i < colors.length; i++) {
			bytes[i * BYTES_PER_COLOR] = (byte) (colors[i] >> 16);
			bytes[i * BYTES_PER_COLOR + 1] = (byte) (colors[i] >> 8);
			bytes[i * BYTES_PER_COLOR + 2] = (byte) colors[i];
		}
		return bytes;
	}

	private static int[] toColors(byte[] bytes) {
		if (bytes.length != TILE_COLORS * BYTES_PER_COLOR) {
			return null;
		}
		final int[] colors = new int[TILE_COLORS];
		for (int i = 0; i < TILE_COLORS; i++) {
			colors[i] = (bytes[i * BYTES_PER_COLOR] & 0xFF) << 16 | (bytes[i * BYTES_PER_COLOR + 1] & 0xFF) << 8 | bytes[i * BYTES_PER_COLOR + 2] & 0xFF;
		}
		return colors;
	}

	@FunctionalInterface
	public interface EntryConsumer {
		void accept(int kind, long key, long hash, long[] payload);
	}

	@FunctionalInterface
	public interface TileConsumer {
		void accept(long key, int[] colors);
	}
}
