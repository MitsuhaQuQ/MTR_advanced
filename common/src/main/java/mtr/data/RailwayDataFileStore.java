package mtr.data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

public class RailwayDataFileStore implements RailwayDataStore {

	private final Path savePath;

	static final String DATABASE_NAME = "mtr.db";
	static final String[] DATABASE_SUFFIXES = {"", "-wal", "-shm"};

	public RailwayDataFileStore(Path savePath) {
		this.savePath = savePath;
	}

	// This class must not reference SQLite classes, otherwise failing to load them can't be handled here
	// If the database file exists but can't be opened, stop instead of silently starting from older data
	public static RailwayDataStore open(Path savePath, String[] types) {
		final boolean databaseExists = Files.exists(savePath.resolve(DATABASE_NAME));
		try {
			return new RailwayDataSqliteStore(savePath, types);
		} catch (Throwable e) {
			if (databaseExists) {
				throw new IllegalStateException("Could not open the Minecraft Transit Railway database in " + savePath, e);
			} else {
				System.out.println("SQLite is unavailable, using separate files to store Minecraft Transit Railway data in " + savePath);
				e.printStackTrace();
				for (final String databaseSuffix : DATABASE_SUFFIXES) {
					try {
						Files.deleteIfExists(savePath.resolve(DATABASE_NAME + databaseSuffix));
					} catch (Exception deleteException) {
						deleteException.printStackTrace();
					}
				}
				return new RailwayDataFileStore(savePath);
			}
		}
	}

	@Override
	public void forEach(String type, BiConsumer<Long, byte[]> consumer) throws Exception {
		final Path typePath = savePath.resolve(type);
		if (!Files.isDirectory(typePath)) {
			return;
		}

		try (final Stream<Path> pathStream = Files.list(typePath)) {
			pathStream.filter(Files::isDirectory).forEach(idFolder -> {
				try (final Stream<Path> folderStream = Files.list(idFolder)) {
					folderStream.filter(RailwayDataFileStore::isDataFile).forEach(idFile -> {
						try {
							consumer.accept(Long.parseLong(idFile.getFileName().toString()), Files.readAllBytes(idFile));
						} catch (Exception e) {
							System.out.println("Skipping invalid Minecraft Transit Railway data file " + idFile);
							e.printStackTrace();
						}
					});
				} catch (Exception e) {
					e.printStackTrace();
				}
			});
		}
	}

	@Override
	public void write(String type, long id, byte[] data) throws Exception {
		final Path parentPath = savePath.resolve(type).resolve(String.valueOf(id % 100));
		Files.createDirectories(parentPath);
		Files.write(parentPath.resolve(String.valueOf(id)), data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
	}

	@Override
	public void delete(String type, long id) throws Exception {
		Files.deleteIfExists(savePath.resolve(type).resolve(String.valueOf(id % 100)).resolve(String.valueOf(id)));
	}

	@Override
	public void commit() {
	}

	@Override
	public void backup() {
	}

	@Override
	public void close() {
	}

	// Data files are named after their ID, so that other files in the folder (such as ._123 and .DS_Store from macOS) are never read
	private static boolean isDataFile(Path path) {
		return Files.isRegularFile(path) && path.getFileName().toString().matches("-?\\d+");
	}
}
