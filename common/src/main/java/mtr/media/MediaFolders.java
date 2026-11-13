package mtr.media;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Folders for uploaded audio and pictures (0.74.0, user 2026-11-13): under each uploader, folders inside folders (at most
 * {@link #MAX_DEPTH} deep), kept when empty. A file's folder only sorts it: files are still named by who uploaded them and
 * their file name, so speakers, announcers and paintings are not touched by moving them. A folder belongs to one uploader
 * and holds only that uploader's files.
 * <p>
 * A folder is written as its path from the uploader's top, names joined by {@code /} ({@code ""} is the top). Both
 * databases keep folders the same way through {@link Store}: a table of folders (so empty ones are kept) and a
 * {@code folder} column in the table of files.
 */
public final class MediaFolders {

	public static final int MAX_DEPTH = 8;
	public static final int MAX_NAME_LENGTH = 32;
	public static final int MAX_PATH_LENGTH = MAX_DEPTH * (MAX_NAME_LENGTH + 1);
	/** The translation key of every refusal for a bad folder name */
	public static final String INVALID = "gui.mtr.media_folder_invalid";

	private MediaFolders() {
	}

	/**
	 * A folder path as it is kept: each name trimmed, empty names (doubled or edge slashes) dropped.
	 *
	 * @throws IllegalArgumentException ({@link #INVALID}) for a name too long, with control characters or {@code \}, a name
	 *                                  of only dots, or too many levels
	 */
	public static String normalize(String path) {
		if (path == null) {
			return "";
		}
		final List<String> names = new ArrayList<>();
		for (final String part : path.split("/")) {
			final String name = part.trim();
			if (name.isEmpty()) {
				continue;
			}
			checkName(name);
			names.add(name);
		}
		if (names.size() > MAX_DEPTH) {
			throw new IllegalArgumentException(INVALID);
		}
		return String.join("/", names);
	}

	/**
	 * One folder name (not a path).
	 *
	 * @throws IllegalArgumentException ({@link #INVALID}) as for {@link #normalize}, or if it has a {@code /} or is empty
	 */
	public static String checkName(String name) {
		final String trimmed = name == null ? "" : name.trim();
		if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH || trimmed.chars().allMatch(c -> c == '.')) {
			throw new IllegalArgumentException(INVALID);
		}
		for (int i = 0; i < trimmed.length(); i++) {
			final char c = trimmed.charAt(i);
			if (c == '/' || c == '\\' || Character.isISOControl(c)) {
				throw new IllegalArgumentException(INVALID);
			}
		}
		return trimmed;
	}

	public static String parent(String path) {
		final int slash = path.lastIndexOf('/');
		return slash < 0 ? "" : path.substring(0, slash);
	}

	public static String name(String path) {
		return path.substring(path.lastIndexOf('/') + 1);
	}

	public static String child(String parent, String name) {
		return parent.isEmpty() ? name : parent + "/" + name;
	}

	public static int depth(String path) {
		return path.isEmpty() ? 0 : path.split("/").length;
	}

	/**
	 * Whether a path is a folder or somewhere inside it. Names are matched without case, as folders that differ only in
	 * case would look the same.
	 */
	public static boolean within(String path, String folder) {
		final String lowerPath = path.toLowerCase(Locale.ROOT);
		final String lowerFolder = folder.toLowerCase(Locale.ROOT);
		return folder.isEmpty() || lowerPath.equals(lowerFolder) || lowerPath.startsWith(lowerFolder + "/");
	}

	/**
	 * Where a path inside {@code from} goes when {@code from} becomes {@code to}.
	 */
	public static String moved(String path, String from, String to) {
		return path.length() == from.length() ? to : child(to, path.substring(from.length() + 1));
	}

	/**
	 * Where a path inside a folder being deleted goes: everything in it moves up a level, into the deleted folder's parent.
	 */
	public static String afterDelete(String path, String deleted) {
		return path.length() == deleted.length() ? parent(deleted) : child(parent(deleted), path.substring(deleted.length() + 1));
	}

	/**
	 * Every folder an uploader has: the kept ones and the ones their files are in, each with its parents, in path order.
	 */
	public static List<String> withParents(Collection<String> paths) {
		final Map<String, String> result = new LinkedHashMap<>();
		paths.stream().sorted(String.CASE_INSENSITIVE_ORDER).forEach(path -> {
			String current = path;
			while (!current.isEmpty()) {
				result.putIfAbsent(current.toLowerCase(Locale.ROOT), current);
				current = parent(current);
			}
		});
		final List<String> list = new ArrayList<>(result.values());
		list.sort(String.CASE_INSENSITIVE_ORDER);
		return list;
	}

	/** Folder actions, as both servers number them in their packets */
	public static final int CREATE = 0;
	public static final int RENAME = 1;
	public static final int DELETE = 2;
	public static final int MOVE = 3;
	public static final String DONE = "gui.mtr.media_folder_done";
	public static final String FAILED = "gui.mtr.media_folder_failed";
	public static final String NO_PERMISSION = "gui.mtr.media_folder_no_permission";

	/**
	 * Runs one folder action of an owner's; whether the player may is checked before.
	 *
	 * @param path      the folder acted on (create: the folder to make it in)
	 * @param argument  create and rename: the new folder's name; move: the folder to move the files to
	 * @param keys      move: the files' name keys
	 * @param ownerOf   the uploader of a file by name key, or null if there is none
	 * @param ownerPaths every folder the owner has now (kept or with files)
	 * @return the translation key of what happened: {@link #DONE} or why not
	 */
	public static String apply(Store store, int action, String owner, String ownerName, String path, String argument, Collection<String> keys, java.util.function.Function<String, String> ownerOf, Collection<String> ownerPaths) {
		try {
			final String folder = normalize(path);
			switch (action) {
				case CREATE:
					final String created = normalize(child(folder, checkName(argument)));
					if (ownerPaths.stream().anyMatch(created::equalsIgnoreCase)) {
						throw new IllegalArgumentException(INVALID);
					}
					store.create(owner, ownerName, created);
					break;
				case RENAME:
					store.rename(owner, ownerName, folder, child(parent(folder), checkName(argument)), ownerPaths);
					break;
				case DELETE:
					store.delete(owner, ownerName, folder);
					break;
				case MOVE:
					for (final String key : keys) {
						if (!owner.equals(ownerOf.apply(key))) {
							return NO_PERMISSION;
						}
					}
					store.move(owner, ownerName, keys, argument);
					break;
				default:
					return FAILED;
			}
			return DONE;
		} catch (IllegalArgumentException e) {
			return INVALID;
		} catch (Exception e) {
			e.printStackTrace();
			return FAILED;
		}
	}

	public static final class Folder {

		public final String owner;
		public final String ownerName;
		public final String path;

		public Folder(String owner, String ownerName, String path) {
			this.owner = owner;
			this.ownerName = ownerName;
			this.path = path;
		}
	}

	/**
	 * The folders of one database: a table of kept folders, and the {@code folder} column of its table of files (added to
	 * older databases, every old file at its uploader's top). Changes to many rows go together in one transaction.
	 */
	public static final class Store {

		private final Connection connection;
		private final String folderTable;
		private final String fileTable;

		/**
		 * @param fileTable the table of files, which has the columns {@code name_key} and {@code uploader}
		 */
		public Store(Connection connection, String folderTable, String fileTable) throws SQLException {
			this.connection = connection;
			this.folderTable = folderTable;
			this.fileTable = fileTable;
			try (final Statement statement = connection.createStatement()) {
				statement.execute("CREATE TABLE IF NOT EXISTS " + folderTable + " (owner TEXT NOT NULL, path TEXT NOT NULL, owner_name TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY (owner, path)) WITHOUT ROWID");
				final Set<String> columns = new HashSet<>();
				try (final ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + fileTable + ")")) {
					while (resultSet.next()) {
						columns.add(resultSet.getString("name"));
					}
				}
				if (!columns.contains("folder")) {
					statement.execute("ALTER TABLE " + fileTable + " ADD COLUMN folder TEXT NOT NULL DEFAULT ''");
				}
			}
		}

		/**
		 * The kept folders, by owner and path.
		 */
		public List<Folder> folders() throws SQLException {
			final List<Folder> folders = new ArrayList<>();
			try (final Statement statement = connection.createStatement(); final ResultSet resultSet = statement.executeQuery("SELECT owner, owner_name, path FROM " + folderTable + " ORDER BY owner, path")) {
				while (resultSet.next()) {
					folders.add(new Folder(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3)));
				}
			}
			return folders;
		}

		/**
		 * The folder of each file, by name key.
		 */
		public Map<String, String> fileFolders() throws SQLException {
			final Map<String, String> folders = new LinkedHashMap<>();
			try (final Statement statement = connection.createStatement(); final ResultSet resultSet = statement.executeQuery("SELECT name_key, folder FROM " + fileTable)) {
				while (resultSet.next()) {
					folders.put(resultSet.getString(1), resultSet.getString(2));
				}
			}
			return folders;
		}

		/**
		 * Keeps a folder and its parents (already kept ones are left as they are).
		 */
		public void create(String owner, String ownerName, String path) throws SQLException {
			inTransaction(() -> insertWithParents(owner, ownerName, normalize(path)));
		}

		/**
		 * Gives a folder another path, and everything in it with it.
		 *
		 * @param paths every folder the owner has now (kept or with files), to look for one in the way
		 * @throws IllegalArgumentException ({@link #INVALID}) if the new path is inside the folder itself, too deep for what is
		 *                                  in it, or already a folder
		 */
		public void rename(String owner, String ownerName, String from, String to, Collection<String> paths) throws SQLException {
			final String fromPath = normalize(from);
			final String toPath = normalize(to);
			if (fromPath.isEmpty() || toPath.isEmpty() || within(toPath, fromPath) && !toPath.equalsIgnoreCase(fromPath)) {
				throw new IllegalArgumentException(INVALID);
			}
			final int extraDepth = depth(toPath) - depth(fromPath);
			for (final String path : paths) {
				if (within(path, fromPath) && depth(path) + extraDepth > MAX_DEPTH) {
					throw new IllegalArgumentException(INVALID);
				}
				// Only a change of case may land on the folder itself
				if (path.equalsIgnoreCase(toPath) && !toPath.equalsIgnoreCase(fromPath)) {
					throw new IllegalArgumentException(INVALID);
				}
			}
			inTransaction(() -> moveAll(owner, ownerName, fromPath, path -> moved(path, fromPath, toPath)));
		}

		/**
		 * Deletes a folder: its files and folders move up a level, into its parent, so no file is ever deleted with a folder.
		 * Folders that then meet one of the same name are merged.
		 */
		public void delete(String owner, String ownerName, String path) throws SQLException {
			final String deleted = normalize(path);
			if (deleted.isEmpty()) {
				throw new IllegalArgumentException(INVALID);
			}
			inTransaction(() -> moveAll(owner, ownerName, deleted, kept -> afterDelete(kept, deleted)));
		}

		/**
		 * Puts files into a folder, which is kept from then on. The caller checks the files are the owner's.
		 */
		public void move(String owner, String ownerName, Collection<String> nameKeys, String folder) throws SQLException {
			final String target = normalize(folder);
			inTransaction(() -> {
				insertWithParents(owner, ownerName, target);
				try (final PreparedStatement statement = connection.prepareStatement("UPDATE " + fileTable + " SET folder = ? WHERE name_key = ? AND uploader = ?")) {
					for (final String nameKey : nameKeys) {
						statement.setString(1, target);
						statement.setString(2, nameKey);
						statement.setString(3, owner);
						statement.executeUpdate();
					}
				}
			});
		}

		/**
		 * Sets one file's folder without checks or a transaction, for writing a file's row (its folder kept).
		 */
		public void setFolder(String nameKey, String folder) throws SQLException {
			try (final PreparedStatement statement = connection.prepareStatement("UPDATE " + fileTable + " SET folder = ? WHERE name_key = ?")) {
				statement.setString(1, folder);
				statement.setString(2, nameKey);
				statement.executeUpdate();
			}
		}

		/**
		 * Keeps a folder and its parents, inside a transaction the caller runs.
		 */
		public void insertWithParents(String owner, String ownerName, String path) throws SQLException {
			try (final PreparedStatement statement = connection.prepareStatement("INSERT OR IGNORE INTO " + folderTable + " (owner, path, owner_name, created_at) VALUES (?, ?, ?, ?)")) {
				for (String current = path; !current.isEmpty(); current = parent(current)) {
					statement.setString(1, owner);
					statement.setString(2, current);
					statement.setString(3, ownerName);
					statement.setLong(4, System.currentTimeMillis());
					statement.executeUpdate();
				}
			}
		}

		private void moveAll(String owner, String ownerName, String folder, java.util.function.UnaryOperator<String> newPath) throws SQLException {
			// Kept folders inside it
			final List<String> keptPaths = new ArrayList<>();
			try (final PreparedStatement statement = connection.prepareStatement("SELECT path FROM " + folderTable + " WHERE owner = ?")) {
				statement.setString(1, owner);
				try (final ResultSet resultSet = statement.executeQuery()) {
					while (resultSet.next()) {
						if (within(resultSet.getString(1), folder)) {
							keptPaths.add(resultSet.getString(1));
						}
					}
				}
			}
			try (final PreparedStatement delete = connection.prepareStatement("DELETE FROM " + folderTable + " WHERE owner = ? AND path = ?")) {
				for (final String path : keptPaths) {
					delete.setString(1, owner);
					delete.setString(2, path);
					delete.executeUpdate();
				}
			}
			for (final String path : keptPaths) {
				insertWithParents(owner, ownerName, newPath.apply(path));
			}
			// Files inside it
			final Map<String, String> files = new LinkedHashMap<>();
			try (final PreparedStatement statement = connection.prepareStatement("SELECT name_key, folder FROM " + fileTable + " WHERE uploader = ?")) {
				statement.setString(1, owner);
				try (final ResultSet resultSet = statement.executeQuery()) {
					while (resultSet.next()) {
						if (!resultSet.getString(2).isEmpty() && within(resultSet.getString(2), folder)) {
							files.put(resultSet.getString(1), resultSet.getString(2));
						}
					}
				}
			}
			for (final Map.Entry<String, String> file : files.entrySet()) {
				setFolder(file.getKey(), newPath.apply(file.getValue()));
			}
		}

		private void inTransaction(SqlAction action) throws SQLException {
			final boolean autoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			try {
				action.run();
				connection.commit();
			} catch (SQLException | RuntimeException e) {
				connection.rollback();
				throw e;
			} finally {
				connection.setAutoCommit(autoCommit);
			}
		}
	}

	@FunctionalInterface
	private interface SqlAction {
		void run() throws SQLException;
	}
}
