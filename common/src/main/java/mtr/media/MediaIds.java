package mtr.media;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * A stable id for each uploaded picture or audio file, and the names it had before (0.79.0, user 2026-11-14: 以资源 id 为准，
 * 文件名可变). A file replaced by one with another name keeps its id, folder and sound id, and its old name stays its own:
 * what the world refers to it by (paintings, speakers, announcers keep the name they were set with) still finds it, and no
 * other file may take that name until the file is deleted.
 */
public final class MediaIds {

	public static final String NAME_USED = "gui.mtr.media_name_used";
	private static final String LETTERS = "abcdefghijkmnpqrstuvwxyz23456789";
	private static final SecureRandom RANDOM = new SecureRandom();

	private MediaIds() {
	}

	/**
	 * @return a new id, eight letters and digits that can't be mistaken for each other
	 */
	public static String newId(Set<String> taken) {
		while (true) {
			final StringBuilder builder = new StringBuilder();
			for (int i = 0; i < 8; i++) {
				builder.append(LETTERS.charAt(RANDOM.nextInt(LETTERS.length())));
			}
			if (!taken.contains(builder.toString())) {
				return builder.toString();
			}
		}
	}

	public static boolean isId(String id) {
		return id != null && id.matches("[a-z0-9]{1,16}");
	}

	/**
	 * Finds what a reference means: the entry with that name now, or else the one that had it before.
	 *
	 * @param oldNames each old name in lowercase, to the id of the file that had it
	 * @return the entry, or null if none has or had that name
	 */
	public static <E> E resolve(String reference, Collection<E> entries, Function<E, String> name, Function<E, String> id, Map<String, String> oldNames) {
		if (reference == null || reference.isEmpty()) {
			return null;
		}
		for (final E entry : entries) {
			if (name.apply(entry).equalsIgnoreCase(reference)) {
				return entry;
			}
		}
		final String owner = oldNames.get(reference.toLowerCase(Locale.ROOT));
		if (owner == null) {
			return null;
		}
		for (final E entry : entries) {
			if (owner.equals(id.apply(entry))) {
				return entry;
			}
		}
		return null;
	}

	/**
	 * The ids and old names of one index table, kept in the same database as it.
	 */
	public static final class Store {

		private final Connection connection;
		private final String indexTable;
		private final String oldNameTable;

		/**
		 * Adds the id column to the index if it hasn't one, gives every file without an id one, and makes the table of old
		 * names.
		 */
		public Store(Connection connection, String indexTable, String oldNameTable) throws SQLException {
			this.connection = connection;
			this.indexTable = indexTable;
			this.oldNameTable = oldNameTable;
			try (final Statement statement = connection.createStatement()) {
				final Set<String> columns = new HashSet<>();
				try (final ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + indexTable + ")")) {
					while (resultSet.next()) {
						columns.add(resultSet.getString("name"));
					}
				}
				if (!columns.contains("resource_id")) {
					statement.execute("ALTER TABLE " + indexTable + " ADD COLUMN resource_id TEXT NOT NULL DEFAULT ''");
				}
				statement.execute("CREATE TABLE IF NOT EXISTS " + oldNameTable + " (name_key TEXT PRIMARY KEY, name TEXT NOT NULL, resource_id TEXT NOT NULL)");
			}
			final List<String> withoutId = new ArrayList<>();
			try (final Statement statement = connection.createStatement(); final ResultSet resultSet = statement.executeQuery("SELECT name_key FROM " + indexTable + " WHERE resource_id = '' ORDER BY name_key")) {
				while (resultSet.next()) {
					withoutId.add(resultSet.getString(1));
				}
			}
			if (!withoutId.isEmpty()) {
				final Set<String> taken = ids();
				try (final PreparedStatement statement = connection.prepareStatement("UPDATE " + indexTable + " SET resource_id = ? WHERE name_key = ?")) {
					for (final String nameKey : withoutId) {
						final String id = newId(taken);
						taken.add(id);
						statement.setString(1, id);
						statement.setString(2, nameKey);
						statement.executeUpdate();
					}
				}
			}
		}

		private Set<String> ids() throws SQLException {
			final Set<String> ids = new HashSet<>();
			try (final Statement statement = connection.createStatement(); final ResultSet resultSet = statement.executeQuery("SELECT resource_id FROM " + indexTable)) {
				while (resultSet.next()) {
					ids.add(resultSet.getString(1));
				}
			}
			return ids;
		}

		/**
		 * @return the id of the file with this name now, or a new one if there is none
		 */
		public String idFor(String nameKey) throws SQLException {
			try (final PreparedStatement statement = connection.prepareStatement("SELECT resource_id FROM " + indexTable + " WHERE name_key = ?")) {
				statement.setString(1, nameKey);
				try (final ResultSet resultSet = statement.executeQuery()) {
					if (resultSet.next() && !resultSet.getString(1).isEmpty()) {
						return resultSet.getString(1);
					}
				}
			}
			return newId(ids());
		}

		/**
		 * @return the id of the file that had this name before, or null
		 */
		public String oldNameOwner(String nameKey) throws SQLException {
			try (final PreparedStatement statement = connection.prepareStatement("SELECT resource_id FROM " + oldNameTable + " WHERE name_key = ?")) {
				statement.setString(1, nameKey.toLowerCase(Locale.ROOT));
				try (final ResultSet resultSet = statement.executeQuery()) {
					return resultSet.next() ? resultSet.getString(1) : null;
				}
			}
		}

		/**
		 * Keeps a name the file had, and lets it have the name it takes now again, if it had it before.
		 */
		public void rename(String id, String oldName, String newName) throws SQLException {
			try (final PreparedStatement statement = connection.prepareStatement("DELETE FROM " + oldNameTable + " WHERE name_key = ?")) {
				statement.setString(1, newName.toLowerCase(Locale.ROOT));
				statement.executeUpdate();
			}
			if (!oldName.equalsIgnoreCase(newName)) {
				try (final PreparedStatement statement = connection.prepareStatement("INSERT OR REPLACE INTO " + oldNameTable + " (name_key, name, resource_id) VALUES (?, ?, ?)")) {
					statement.setString(1, oldName.toLowerCase(Locale.ROOT));
					statement.setString(2, oldName);
					statement.setString(3, id);
					statement.executeUpdate();
				}
			}
		}

		/**
		 * Frees the old names of a file deleted.
		 */
		public void forget(String id) throws SQLException {
			try (final PreparedStatement statement = connection.prepareStatement("DELETE FROM " + oldNameTable + " WHERE resource_id = ?")) {
				statement.setString(1, id);
				statement.executeUpdate();
			}
		}

		/**
		 * @return each id with the old names of its file, oldest first
		 */
		public Map<String, List<String>> oldNames() throws SQLException {
			final Map<String, List<String>> oldNames = new TreeMap<>();
			try (final Statement statement = connection.createStatement(); final ResultSet resultSet = statement.executeQuery("SELECT resource_id, name FROM " + oldNameTable + " ORDER BY rowid")) {
				while (resultSet.next()) {
					oldNames.computeIfAbsent(resultSet.getString(1), key -> new ArrayList<>()).add(resultSet.getString(2));
				}
			}
			return oldNames;
		}
	}

	/**
	 * @param oldNames each id with the old names of its file
	 * @return each old name in lowercase with the id of its file, for {@link #resolve}
	 */
	public static Map<String, String> byOldName(Map<String, List<String>> oldNames) {
		final Map<String, String> byName = new TreeMap<>();
		oldNames.forEach((id, names) -> names.forEach(name -> byName.put(name.toLowerCase(Locale.ROOT), id)));
		return byName;
	}
}
